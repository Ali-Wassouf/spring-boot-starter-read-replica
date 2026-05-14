package space.aliwasouf.readreplica;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.hibernate.Session;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

/**
 * Routes annotated methods to the read replica.
 *
 * <p>Precedence rules (see PLAN.md §4 Phase 2):
 * <ul>
 *   <li>If the method (or its class) carries {@link Transactional @Transactional}
 *       with {@code readOnly=false} (the default), the routing switch is skipped
 *       — writes must go to master.</li>
 *   <li>If an outer write transaction is already active when the @ReadOnly
 *       method is invoked, the switch is skipped for the same reason.</li>
 *   <li>{@code @Transactional(readOnly = true)} is treated as a hint and does
 *       not block routing to the replica.</li>
 * </ul>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ReadOnlyAspect {

    private final ObjectProvider<EntityManagerFactory> entityManagerFactory;

    public ReadOnlyAspect(ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Around(
            "@annotation(space.aliwasouf.readreplica.ReadOnly) "
                    + "|| @within(space.aliwasouf.readreplica.ReadOnly)"
    )
    public Object routeToReplica(ProceedingJoinPoint pjp) throws Throwable {
        Method invoked = ((MethodSignature) pjp.getSignature()).getMethod();
        Class<?> targetClass = pjp.getTarget() != null
                ? pjp.getTarget().getClass()
                : invoked.getDeclaringClass();
        Method target = AopUtils.getMostSpecificMethod(invoked, targetClass);

        if (writeTransactionWins(target)) {
            return pjp.proceed();
        }

        RoutingTarget prior = RoutingContext.get();
        RoutingContext.set(RoutingTarget.REPLICA);
        HibernateReadOnlyHandle hibernate = applyHibernateReadOnly();
        try {
            return pjp.proceed();
        } finally {
            if (hibernate != null) {
                hibernate.restore();
            }
            if (prior == null) {
                RoutingContext.clear();
            } else {
                RoutingContext.set(prior);
            }
        }
    }

    private static boolean writeTransactionWins(Method method) {
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        if (tx != null && !tx.readOnly()) {
            return true;
        }
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    private HibernateReadOnlyHandle applyHibernateReadOnly() {
        EntityManagerFactory factory = entityManagerFactory.getIfAvailable();
        if (factory == null) {
            return null;
        }
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(factory);
        if (holder == null) {
            return null;
        }
        EntityManager em = holder.getEntityManager();
        try {
            Session session = em.unwrap(Session.class);
            boolean prior = session.isDefaultReadOnly();
            session.setDefaultReadOnly(true);
            return new HibernateReadOnlyHandle(session, prior);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record HibernateReadOnlyHandle(Session session, boolean prior) {
        void restore() {
            session.setDefaultReadOnly(prior);
        }
    }
}
