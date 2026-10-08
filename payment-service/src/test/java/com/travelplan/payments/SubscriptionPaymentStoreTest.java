package com.travelplan.payments;

import com.travelplan.payments.SubscriptionPayment.State;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SQL store against the real V5 migration (H2 in PostgreSQL mode): the database enforces the rules too. */
class SubscriptionPaymentStoreTest {
    private JdbcTemplate jdbc;
    private SubscriptionPaymentStore store;

    @BeforeEach void schema() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:payments-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V5__subscription_payments.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        store = new SubscriptionPaymentStore(jdbc);
    }

    @Test void onePaymentPerSubscriptionAndPerIdempotencyKey() {
        UUID user = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        SubscriptionPayment first = payment(user, subscription, "key-1");
        store.insert(first);

        assertThatThrownBy(() -> store.insert(payment(user, subscription, "key-2"))).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> store.insert(payment(user, UUID.randomUUID(), "key-1"))).isInstanceOf(DuplicateKeyException.class);
        store.insert(payment(UUID.randomUUID(), UUID.randomUUID(), "key-1"));

        assertThat(store.byIdempotencyKey(user, "key-1")).contains(first);
        assertThat(store.bySubscription(subscription)).contains(first);
        assertThat(store.forUser(user)).containsExactly(first);
    }

    @Test void updatesAreOptimisticSoOnlyOneWorkerMovesAPayment() {
        SubscriptionPayment processing = payment(UUID.randomUUID(), UUID.randomUUID(), "key");
        store.insert(processing);

        SubscriptionPayment succeeded = processing.with(State.SUCCEEDED, "pi_123", null);
        assertThat(store.update(succeeded, processing)).isTrue();
        assertThat(store.update(processing.with(State.FAILED, null, "PAYMENT_DECLINED"), processing)).isFalse();
        assertThat(store.byId(processing.paymentId()).orElseThrow().state()).isEqualTo(State.SUCCEEDED);

        SubscriptionPayment claimed = succeeded.refundPending("SUBSCRIPTION_CANCELLED").refundAttempt();
        assertThat(store.update(succeeded.refundPending("SUBSCRIPTION_CANCELLED"), succeeded)).isTrue();
        assertThat(store.update(claimed, store.byId(processing.paymentId()).orElseThrow())).isTrue();
        assertThat(store.byId(processing.paymentId()).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test void reconciliationFindsLostAnswersStaleWorkUndeliveredOutcomesAndPendingRefunds() {
        SubscriptionPayment fresh = payment(UUID.randomUUID(), UUID.randomUUID(), "fresh");
        SubscriptionPayment lost = payment(UUID.randomUUID(), UUID.randomUUID(), "lost");
        SubscriptionPayment undelivered = payment(UUID.randomUUID(), UUID.randomUUID(), "undelivered");
        SubscriptionPayment done = payment(UUID.randomUUID(), UUID.randomUUID(), "done");
        for (SubscriptionPayment p : new SubscriptionPayment[] {fresh, lost, undelivered, done}) store.insert(p);
        store.update(lost.with(State.REQUIRES_RECONCILIATION, null, "PROVIDER_NO_ANSWER"), lost);
        store.update(undelivered.with(State.SUCCEEDED, "pi_1", null), undelivered);
        store.update(done.with(State.SUCCEEDED, "pi_2", null).delivered(), done);

        assertThat(store.needingWork(Instant.now().minusSeconds(120))).extracting(SubscriptionPayment::paymentId)
                .containsExactlyInAnyOrder(lost.paymentId(), undelivered.paymentId());
        // A PROCESSING row counts once it is older than the stale limit (its request thread died).
        assertThat(store.needingWork(Instant.now().plusSeconds(1))).extracting(SubscriptionPayment::paymentId)
                .contains(fresh.paymentId()).doesNotContain(done.paymentId());
    }

    @Test void theSchemaHasNoColumnForCardOrAccountData() {
        var columns = jdbc.queryForList("SELECT LOWER(column_name) FROM information_schema.columns "
                + "WHERE LOWER(table_name) = 'subscription_payment'", String.class);
        assertThat(columns).noneMatch(name -> name.contains("card") || name.contains("pan") || name.contains("cvc")
                || name.contains("secret") || name.contains("password") || name.contains("account_number"));
    }

    private static SubscriptionPayment payment(UUID user, UUID subscription, String key) {
        // The database keeps microseconds; Linux clocks give nanoseconds, so compare at the stored precision.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new SubscriptionPayment(UUID.randomUUID(), subscription, user, 45050, "EUR", "TEST", null, "APPROVE",
                "Test card", key, "hash", State.PROCESSING, null, null, false, null, null, 1, now, now);
    }
}
