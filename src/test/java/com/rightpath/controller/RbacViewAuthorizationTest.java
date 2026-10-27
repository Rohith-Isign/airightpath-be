package com.rightpath.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.rightpath.rbac.PermissionName;

/**
 * Who may read another account's roles and permissions.
 *
 * <p>These two endpoints were guarded by {@code USER_READ}, which candidates are
 * seeded with so they can read their own profile. The effect was that any
 * candidate could pass any other address to {@code /api/admin/rbac/user} and
 * read back that account's exact roles and permissions — telling them which
 * accounts hold privilege and which authority names the application checks. No
 * escalation, but a straight disclosure, and a useful map for anyone looking for
 * somewhere to escalate.</p>
 *
 * <p>The expression is not merely compared as a string here, it is evaluated:
 * the guard now has two branches, and a string comparison would pass while the
 * branch that matters silently granted or denied the wrong callers. Evaluating
 * it also proves {@code #email} actually resolves — if parameter names were not
 * retained at compile time the expression would fail at runtime, turning the
 * endpoint into a 500 rather than a 403.</p>
 */
class RbacViewAuthorizationTest {

    private static final String CANDIDATE = "candidate@example.test";
    private static final String SOMEONE_ELSE = "superadmin@example.test";

    // Which roles hold USER_LIST is asserted in RbacPermissionSplitTest, in the
    // package that can see the seeded sets. This class covers the other half:
    // what the guard does with the authorities a caller turns up holding.

    @Test
    void aCandidateCannotReadAnotherAccountsRbac() {
        assertFalse(permits(getUserRbac(), candidateAuth(), SOMEONE_ELSE),
                "this is the disclosure: a candidate must not read anyone else's roles and permissions");
    }

    @Test
    void aCandidateCanStillReadTheirOwn() {
        assertTrue(permits(getUserRbac(), candidateAuth(), CANDIDATE),
                "a caller reading their own entry learns nothing new, so it stays allowed");
    }

    @Test
    void theSelfReadIgnoresAddressCase() {
        // An address differing only in case is the same account, and denying it
        // would be a confusing 403 rather than a security gain.
        assertTrue(permits(getUserRbac(), candidateAuth(), CANDIDATE.toUpperCase()));
    }

    @Test
    void staffCanReadAnyAccount() {
        Authentication admin = authWith(CANDIDATE, PermissionName.USER_LIST.name());
        assertTrue(permits(getUserRbac(), admin, SOMEONE_ELSE),
                "USER_LIST is what an admin screen needs in order to show other people's roles");
    }

    @Test
    void listingRoleNamesIsStaffOnly() {
        assertEquals("hasAuthority('" + PermissionName.USER_LIST + "')", preAuthorizeOf(methodOf("listRoles")),
                "the role list only fills a staff-only picker, and it names the privileged roles");
    }

    @Test
    void bothReadEndpointsAreStillGuarded() {
        // An endpoint that loses its annotation altogether passes every
        // functional test while being open to any authenticated caller.
        assertNotNull(methodOf("listRoles").getAnnotation(PreAuthorize.class));
        assertNotNull(getUserRbac().getAnnotation(PreAuthorize.class));
    }

    // ── plumbing ──────────────────────────────────────────────────────

    private static Method getUserRbac() {
        return methodOf("getUserRbac", String.class);
    }

    private static Method methodOf(String name, Class<?>... parameterTypes) {
        try {
            return RbacAdminController.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("endpoint method " + name + " not found", e);
        }
    }

    private static String preAuthorizeOf(Method method) {
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, method.getName() + " must be guarded by @PreAuthorize");
        return annotation.value();
    }

    private static Authentication candidateAuth() {
        return authWith(CANDIDATE, PermissionName.USER_READ.name());
    }

    private static Authentication authWith(String email, String... authorities) {
        return new UsernamePasswordAuthenticationToken(email, "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** Evaluates the endpoint's own {@code @PreAuthorize} for one caller. */
    private static boolean permits(Method method, Authentication caller, Object... arguments) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        EvaluationContext context = handler.createEvaluationContext(caller, invocationOf(method, arguments));
        Boolean granted = handler.getExpressionParser()
                .parseExpression(preAuthorizeOf(method))
                .getValue(context, Boolean.class);
        return Boolean.TRUE.equals(granted);
    }

    private static MethodInvocation invocationOf(Method method, Object... arguments) {
        // The expression handler builds its root object from the invocation
        // target, so getThis() has to be a real instance. Its service is null
        // because the guard is evaluated and the endpoint body never runs.
        RbacAdminController target = new RbacAdminController(null);
        return new MethodInvocation() {
            @Override
            public Method getMethod() {
                return method;
            }

            @Override
            public Object[] getArguments() {
                return arguments;
            }

            @Override
            public Object proceed() {
                throw new UnsupportedOperationException("the guard is evaluated, the endpoint is never called");
            }

            @Override
            public Object getThis() {
                return target;
            }

            @Override
            public AccessibleObject getStaticPart() {
                return method;
            }
        };
    }
}
