package com.altrium.plan;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.Role;
import com.altrium.testsupport.Acting;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-5.7 under genuine concurrency, against real MySQL.
 *
 * <p><strong>This test class is deliberately not {@code @Transactional}.</strong> Every other
 * test in the suite runs inside one transaction that rolls back, which is exactly the thing
 * that would make this one meaningless: two "concurrent" calls sharing a single transaction
 * cannot race, so the test would pass without the constraint existing at all.
 *
 * <p>The cost is that rows are really committed, so they are really cleaned up afterwards. That
 * is the price of testing a database guarantee rather than a Java one, and it is why the
 * project runs its tests against MySQL rather than an in-memory substitute: H2 would happily
 * agree that a constraint it implements differently was being enforced.
 *
 * <p>What is under test is the unique index on {@code improvement_plan.active_user_id}, a
 * generated column holding the user id only while the plan is ACTIVE. The service checks for an
 * existing plan first, and that check is a courtesy - two requests can both pass it. The
 * database is what decides.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
class PlanExclusivityConcurrencyTest {

    @Autowired
    private PlanService plans;

    @Autowired
    private OrgFixture org;

    @Autowired
    private Acting acting;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long departmentId;

    @AfterEach
    void cleanUp() {
        acting.clear();
        if (createdUserIds.isEmpty()) {
            return;
        }
        String ids = createdUserIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow();
        jdbc.update("DELETE FROM plan_goal WHERE improvement_plan_id IN"
                + " (SELECT id FROM improvement_plan WHERE user_id IN (" + ids + "))");
        jdbc.update("DELETE FROM plan_goal WHERE development_plan_id IN"
                + " (SELECT id FROM development_plan WHERE user_id IN (" + ids + "))");
        jdbc.update("DELETE FROM improvement_plan WHERE user_id IN (" + ids + ")");
        jdbc.update("DELETE FROM development_plan WHERE user_id IN (" + ids + ")");
        jdbc.update("DELETE FROM user_role WHERE user_id IN (" + ids + ")");
        jdbc.update("UPDATE app_user SET manager_id = NULL WHERE id IN (" + ids + ")");
        jdbc.update("DELETE FROM app_user WHERE id IN (" + ids + ")");
        if (departmentId != null) {
            jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
        }
        createdUserIds.clear();
    }

    @Test
    @DisplayName("P-5.7: two concurrent requests to open an improvement plan produce exactly one")
    void P_5_7_concurrentOpensProduceExactlyOnePlan() throws Exception {
        record People(Long managerId, Long subjectId) {
        }

        People people = transactions.execute(status -> {
            Department engineering = org.department("Engineering");
            AppUser elena = org.userIn(engineering, "elena-conc", Role.EMPLOYEE, Role.MANAGER);
            AppUser john = org.userIn(engineering, "john-conc", Role.EMPLOYEE);
            john.setManager(elena);
            org.flush();
            departmentId = engineering.getId();
            createdUserIds.add(elena.getId());
            createdUserIds.add(john.getId());
            return new People(elena.getId(), john.getId());
        });

        int attempts = 2;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            Thread thread = new Thread(() -> {
                // Each thread establishes its own caller: the security context and the
                // request-scoped HR resolver are both thread-local, so a context set on the
                // main thread would not be visible here anyway.
                acting.asSubject(OrgFixture.subjectOf("elena-conc"));
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    transactions.execute(status -> plans.openImprovementPlan(
                            people.subjectId(),
                            "Sustained improvement required or role at risk.",
                            LocalDate.now().plusMonths(3),
                            plan -> plan.getId()));
                    succeeded.incrementAndGet();
                } catch (Exception ex) {
                    // Either the service's own check or the unique index, depending on which
                    // thread got where first. Both are the same refusal to the caller.
                    refused.incrementAndGet();
                } finally {
                    acting.clear();
                }
            });
            threads.add(thread);
            thread.start();
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (Thread thread : threads) {
            thread.join(15_000);
        }

        // The assertion that matters is the row count, not the exception count. Whatever the
        // two threads did to each other, the employee ends up on exactly one improvement plan,
        // which is what P-5.7 actually says.
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM improvement_plan WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, people.subjectId());

        assertThat(active).isEqualTo(1);
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(attempts - 1);

        // And the development plan is suspended exactly once, by whichever thread won.
        String pdpStatus = jdbc.queryForObject(
                "SELECT status FROM development_plan WHERE user_id = ?",
                String.class, people.subjectId());
        assertThat(pdpStatus).isEqualTo("SUSPENDED");
    }
}
