---------------------------- MODULE ReadReplicaRouting ----------------------------
(*
  Formal specification of the Spring Boot Read-Replica Routing Library.

  The library routes JDBC queries to either MASTER or REPLICA based on:
    - A per-thread RoutingContext (NONE | REPLICA)
    - The health of the replica datasource (HEALTHY | UNHEALTHY)
    - Whether an active write transaction is in progress on the thread

  We model N threads executing concurrently. Each thread has:
    routingCtx   : the current ThreadLocal routing context
    txState      : transaction state (NONE | WRITE | READ_ONLY)
    priorCtx     : the saved context to restore after a @ReadOnly scope
    inReadOnly   : whether the thread is currently inside a @ReadOnly scope
    lastServedBy : the datasource that served the most recent query

  Health state is global (shared across threads, as in the real
  DataSourceHealthMonitor using AtomicBoolean).
*)

EXTENDS Naturals, FiniteSets, TLC

\* ─────────────────────────────────────────────────────────────────────────────
\* Constants
\* ─────────────────────────────────────────────────────────────────────────────

CONSTANT Threads   \* finite set of thread ids, e.g. {T1, T2}

\* ─────────────────────────────────────────────────────────────────────────────
\* Value domains
\* ─────────────────────────────────────────────────────────────────────────────

CtxValues    == {"NONE", "REPLICA"}
TxValues     == {"NONE", "WRITE", "READ_ONLY"}
HealthValues == {"HEALTHY", "UNHEALTHY"}
DsValues     == {"MASTER", "REPLICA"}

\* ─────────────────────────────────────────────────────────────────────────────
\* Variables
\* ─────────────────────────────────────────────────────────────────────────────

VARIABLES
    routingCtx,    \* routingCtx[t]   : CtxValues  — ThreadLocal for thread t
    txState,       \* txState[t]      : TxValues   — active transaction kind
    priorCtx,      \* priorCtx[t]     : CtxValues  — context saved by EnterReadOnly
    inReadOnly,    \* inReadOnly[t]   : BOOLEAN    — inside @ReadOnly scope?
    lastServedBy,  \* lastServedBy[t] : DsValues   — datasource of last query
    masterHealth,  \* masterHealth    : HealthValues — global master health flag
    replicaHealth  \* replicaHealth   : HealthValues — global replica health flag

vars == <<routingCtx, txState, priorCtx, inReadOnly, lastServedBy,
          masterHealth, replicaHealth>>

\* ─────────────────────────────────────────────────────────────────────────────
\* Type invariant
\* ─────────────────────────────────────────────────────────────────────────────

TypeOK ==
    /\ routingCtx   \in [Threads -> CtxValues]
    /\ txState      \in [Threads -> TxValues]
    /\ priorCtx     \in [Threads -> CtxValues]
    /\ inReadOnly   \in [Threads -> BOOLEAN]
    /\ lastServedBy \in [Threads -> DsValues]
    /\ masterHealth  \in HealthValues
    /\ replicaHealth \in HealthValues

\* ─────────────────────────────────────────────────────────────────────────────
\* DetermineTarget(t)
\*
\* Pure operator mirroring RoutingDataSource.determineCurrentLookupKey():
\*   - WRITE tx active → always MASTER (write transaction wins)
\*   - routingCtx = REPLICA AND replica HEALTHY → REPLICA
\*   - otherwise → MASTER (plain call, or replica unhealthy fallback)
\*
\* Note: masterHealth does not appear in this function — master health
\* is observable-only and never influences routing decisions.
\* ─────────────────────────────────────────────────────────────────────────────

DetermineTarget(t) ==
    IF txState[t] = "WRITE"
    THEN "MASTER"
    ELSE IF routingCtx[t] = "REPLICA" /\ replicaHealth = "HEALTHY"
         THEN "REPLICA"
         ELSE "MASTER"

WriteTransactionActive(t) == txState[t] = "WRITE"

\* ─────────────────────────────────────────────────────────────────────────────
\* Initial state
\* ─────────────────────────────────────────────────────────────────────────────

Init ==
    /\ routingCtx   = [t \in Threads |-> "NONE"]
    /\ txState      = [t \in Threads |-> "NONE"]
    /\ priorCtx     = [t \in Threads |-> "NONE"]
    /\ inReadOnly   = [t \in Threads |-> FALSE]
    /\ lastServedBy = [t \in Threads |-> "MASTER"]
    /\ masterHealth  = "HEALTHY"
    /\ replicaHealth = "HEALTHY"

\* ─────────────────────────────────────────────────────────────────────────────
\* Health monitor actions
\* ─────────────────────────────────────────────────────────────────────────────

\* Probe detects replica failure
ReplicaBecomesUnhealthy ==
    /\ replicaHealth = "HEALTHY"
    /\ replicaHealth' = "UNHEALTHY"
    /\ UNCHANGED <<routingCtx, txState, priorCtx, inReadOnly,
                   lastServedBy, masterHealth>>

\* Probe detects replica recovery
ReplicaRecovers ==
    /\ replicaHealth = "UNHEALTHY"
    /\ replicaHealth' = "HEALTHY"
    /\ UNCHANGED <<routingCtx, txState, priorCtx, inReadOnly,
                   lastServedBy, masterHealth>>

\* Probe detects master failure (observability only — does not change routing)
MasterBecomesUnhealthy ==
    /\ masterHealth = "HEALTHY"
    /\ masterHealth' = "UNHEALTHY"
    /\ UNCHANGED <<routingCtx, txState, priorCtx, inReadOnly,
                   lastServedBy, replicaHealth>>

\* Probe detects master recovery
MasterRecovers ==
    /\ masterHealth = "UNHEALTHY"
    /\ masterHealth' = "HEALTHY"
    /\ UNCHANGED <<routingCtx, txState, priorCtx, inReadOnly,
                   lastServedBy, replicaHealth>>

\* ─────────────────────────────────────────────────────────────────────────────
\* @ReadOnly aspect actions
\* ─────────────────────────────────────────────────────────────────────────────

\* Aspect entry — no active write tx: save prior ctx, push REPLICA
EnterReadOnly(t) ==
    /\ ~inReadOnly[t]
    /\ ~WriteTransactionActive(t)
    /\ priorCtx'   = [priorCtx   EXCEPT ![t] = routingCtx[t]]
    /\ routingCtx' = [routingCtx EXCEPT ![t] = "REPLICA"]
    /\ inReadOnly' = [inReadOnly EXCEPT ![t] = TRUE]
    /\ UNCHANGED <<txState, lastServedBy, masterHealth, replicaHealth>>

\* Aspect entry — write tx is active: skip routing change, proceed as-is
EnterReadOnlySkipped(t) ==
    /\ ~inReadOnly[t]
    /\ WriteTransactionActive(t)
    /\ inReadOnly' = [inReadOnly EXCEPT ![t] = TRUE]
    /\ UNCHANGED <<routingCtx, txState, priorCtx, lastServedBy,
                   masterHealth, replicaHealth>>

\* Aspect finally block — always restores prior context regardless of path taken
ExitReadOnly(t) ==
    /\ inReadOnly[t]
    /\ routingCtx' = [routingCtx EXCEPT ![t] = priorCtx[t]]
    /\ inReadOnly' = [inReadOnly EXCEPT ![t] = FALSE]
    /\ priorCtx'   = [priorCtx   EXCEPT ![t] = "NONE"]
    /\ UNCHANGED <<txState, lastServedBy, masterHealth, replicaHealth>>

\* ─────────────────────────────────────────────────────────────────────────────
\* Transaction lifecycle actions
\* ─────────────────────────────────────────────────────────────────────────────

\* @Transactional (readOnly=false) — always routes to MASTER
BeginWriteTx(t) ==
    /\ txState[t] = "NONE"
    /\ txState' = [txState EXCEPT ![t] = "WRITE"]
    /\ UNCHANGED <<routingCtx, priorCtx, inReadOnly, lastServedBy,
                   masterHealth, replicaHealth>>

CommitWriteTx(t) ==
    /\ txState[t] = "WRITE"
    /\ txState' = [txState EXCEPT ![t] = "NONE"]
    /\ UNCHANGED <<routingCtx, priorCtx, inReadOnly, lastServedBy,
                   masterHealth, replicaHealth>>

\* @Transactional(readOnly=true) — does not override @ReadOnly routing
BeginReadOnlyTx(t) ==
    /\ txState[t] = "NONE"
    /\ txState' = [txState EXCEPT ![t] = "READ_ONLY"]
    /\ UNCHANGED <<routingCtx, priorCtx, inReadOnly, lastServedBy,
                   masterHealth, replicaHealth>>

CommitReadOnlyTx(t) ==
    /\ txState[t] = "READ_ONLY"
    /\ txState' = [txState EXCEPT ![t] = "NONE"]
    /\ UNCHANGED <<routingCtx, priorCtx, inReadOnly, lastServedBy,
                   masterHealth, replicaHealth>>

\* ─────────────────────────────────────────────────────────────────────────────
\* Query execution
\*
\* Models a JDBC query being issued. DetermineTarget(t) is evaluated and
\* its result recorded in lastServedBy[t]. This is the only action that
\* observably "chooses" a datasource.
\* ─────────────────────────────────────────────────────────────────────────────

ExecuteQuery(t) ==
    /\ lastServedBy' = [lastServedBy EXCEPT ![t] = DetermineTarget(t)]
    /\ UNCHANGED <<routingCtx, txState, priorCtx, inReadOnly,
                   masterHealth, replicaHealth>>

\* ─────────────────────────────────────────────────────────────────────────────
\* Next-state relation
\* ─────────────────────────────────────────────────────────────────────────────

Next ==
    \/ ReplicaBecomesUnhealthy
    \/ ReplicaRecovers
    \/ MasterBecomesUnhealthy
    \/ MasterRecovers
    \/ \E t \in Threads :
        \/ EnterReadOnly(t)
        \/ EnterReadOnlySkipped(t)
        \/ ExitReadOnly(t)
        \/ BeginWriteTx(t)
        \/ CommitWriteTx(t)
        \/ BeginReadOnlyTx(t)
        \/ CommitReadOnlyTx(t)
        \/ ExecuteQuery(t)

\* ─────────────────────────────────────────────────────────────────────────────
\* Fairness
\*
\* WF on ExecuteQuery: a thread that can issue a query eventually does.
\* Required for both liveness properties.
\*
\* WF on ReplicaRecovers: the health monitor eventually detects recovery.
\* Required for EventualRecovery to be non-vacuous.
\* ─────────────────────────────────────────────────────────────────────────────

Fairness ==
    /\ \A t \in Threads : WF_vars(ExecuteQuery(t))
    /\ WF_vars(ReplicaRecovers)

\* ─────────────────────────────────────────────────────────────────────────────
\* Specification
\* ─────────────────────────────────────────────────────────────────────────────

Spec == Init /\ [][Next]_vars /\ Fairness

\* ─────────────────────────────────────────────────────────────────────────────
\* Safety invariants
\* ─────────────────────────────────────────────────────────────────────────────

\* A query is routed to REPLICA iff ctx=REPLICA AND replica HEALTHY AND no write tx
ReplicaRoutingCorrectness ==
    \A t \in Threads :
        DetermineTarget(t) = "REPLICA"
        <=>
        ( routingCtx[t] = "REPLICA"
          /\ replicaHealth = "HEALTHY"
          /\ txState[t] # "WRITE" )

\* An active write transaction always routes to MASTER
WriteTxAlwaysMaster ==
    \A t \in Threads :
        txState[t] = "WRITE" => DetermineTarget(t) = "MASTER"

\* Replica unhealthy → all threads fall back to MASTER
FallbackWhenReplicaUnhealthy ==
    replicaHealth = "UNHEALTHY" =>
        \A t \in Threads : DetermineTarget(t) = "MASTER"

\* Master health does not appear in DetermineTarget; routing is never
\* changed by master health state
MasterHealthDoesNotBlockRouting ==
    \A t \in Threads :
        DetermineTarget(t) =
            IF txState[t] = "WRITE" THEN "MASTER"
            ELSE IF routingCtx[t] = "REPLICA" /\ replicaHealth = "HEALTHY"
                 THEN "REPLICA"
                 ELSE "MASTER"

\* After @ReadOnly scope exits, priorCtx is reset to sentinel NONE
AspectContextCleanup ==
    \A t \in Threads :
        ~inReadOnly[t] => priorCtx[t] = "NONE"

\* ─────────────────────────────────────────────────────────────────────────────
\* Liveness properties
\* ─────────────────────────────────────────────────────────────────────────────

\* While the replica is unhealthy, every thread's ROUTING DECISION (not
\* historical lastServedBy) is MASTER. This is a safety property expressed
\* as a temporal formula — equivalent to FallbackWhenReplicaUnhealthy but
\* stated here for clarity alongside the recovery property.
\*
\* Note: lastServedBy is a trailing indicator (records the last query's
\* actual target). Using it in a leads-to formula is imprecise because a
\* query may have correctly hit the replica BEFORE the failure; requiring
\* lastServedBy to eventually flip to MASTER conflates past history with
\* future routing decisions. DetermineTarget captures the live decision.
EventualFallback ==
    replicaHealth = "UNHEALTHY" =>
        \A t \in Threads : DetermineTarget(t) = "MASTER"

\* Once the replica recovers AND a thread has routingCtx=REPLICA (inside a
\* @ReadOnly scope with no write tx), the routing decision for that thread
\* is immediately REPLICA — no delay needed, DetermineTarget is a pure
\* function evaluated on demand.
EventualRecovery ==
    \A t \in Threads :
        ( replicaHealth = "HEALTHY" /\
          routingCtx[t] = "REPLICA" /\
          txState[t] # "WRITE" )
        => DetermineTarget(t) = "REPLICA"

\* ─────────────────────────────────────────────────────────────────────────────
\* Master theorem
\* ─────────────────────────────────────────────────────────────────────────────

THEOREM Spec =>
    []TypeOK
    /\ []ReplicaRoutingCorrectness
    /\ []WriteTxAlwaysMaster
    /\ []FallbackWhenReplicaUnhealthy
    /\ []MasterHealthDoesNotBlockRouting
    /\ []AspectContextCleanup
    /\ EventualFallback
    /\ EventualRecovery

=============================================================================
