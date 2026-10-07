package com.travelplan.users;
import com.travelplan.common.security.Role;

import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UserStoreIdentityTest {
    @Test void persistedUserLoadFailsStartupWhenStorageIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(jdbc)
                .query(startsWith("select id,email"), any(org.springframework.jdbc.core.RowCallbackHandler.class));
        @SuppressWarnings("unchecked") ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PlatformTransactionManager> managers = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(managers.getIfAvailable()).thenReturn(mock(PlatformTransactionManager.class));
        UserStore store = new UserStore(mock(RevocationClient.class), mock(AuthProvisionClient.class), provider, managers);

        assertThrows(DataAccessResourceFailureException.class, store::loadPersisted);
    }

    @Test void failedUserInsertDoesNotReserveEmailInLocalCache() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            private int attempts;
            @Override public int update(String sql, Object... args) {
                if (++attempts == 1) throw new DataAccessResourceFailureException("database unavailable");
                return 1;
            }
        };
        @SuppressWarnings("unchecked") ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PlatformTransactionManager> managers = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(managers.getIfAvailable()).thenReturn(mock(PlatformTransactionManager.class));
        RevocationClient revocations = mock(RevocationClient.class);
        AuthProvisionClient provisioning = mock(AuthProvisionClient.class);
        UserStore store = new UserStore(revocations, provisioning, provider, managers);

        assertThrows(DataAccessResourceFailureException.class,
                () -> store.create("retry@example.com", "First", "Last", Role.TRAVELER));
        verify(revocations).revoke(any());
        assertThat(store.create("retry@example.com", "First", "Last", Role.TRAVELER).email())
                .isEqualTo("retry@example.com");
    }

    @Test void failedAuthCompensationPreservesTheStorageError() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(jdbc)
                .update(startsWith("insert into users"), any(Object[].class));
        @SuppressWarnings("unchecked") ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PlatformTransactionManager> managers = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(managers.getIfAvailable()).thenReturn(mock(PlatformTransactionManager.class));
        RevocationClient revocations = mock(RevocationClient.class);
        doThrow(new IllegalStateException("auth unavailable")).when(revocations).revoke(any());
        UserStore store = new UserStore(revocations, mock(AuthProvisionClient.class), provider, managers);

        var failure = assertThrows(DataAccessResourceFailureException.class,
                () -> store.create("retry@example.com", "First", "Last", Role.TRAVELER));
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).hasMessage("auth unavailable");
    }

    @Test void persistentUserCreateBindsTimestamp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PlatformTransactionManager> managers = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(managers.getIfAvailable()).thenReturn(mock(PlatformTransactionManager.class));
        UserStore store = new UserStore(mock(RevocationClient.class), mock(AuthProvisionClient.class), provider, managers);

        var user = store.create("person@example.com", "First", "Last", Role.TRAVELER);

        verify(jdbc).update(startsWith("insert into users"), eq(user.id()), eq(user.email()),
                eq("First"), eq("Last"), eq("TRAVELER"), eq("ACTIVE"),
                eq(Timestamp.from(user.createdAt())));
    }

    @Test void userChangesSynchronizeIdentityBeforeChangingProfile() {
        RevocationClient revocations = mock(RevocationClient.class);
        AuthProvisionClient provisioning = mock(AuthProvisionClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        UserStore store = new UserStore(revocations, provisioning, provider);
        var user = store.create("person@example.com", "First", "Last", Role.TRAVELER);
        verify(provisioning).provision(eq(user.id()), eq(user.email()), anyString(), eq("TRAVELER"));

        var updated = store.update(user.id(), "new@example.com", null, null, Role.ADMIN, null);
        verify(provisioning).update(user.id(), "new@example.com", "ADMIN", true);
        assertThat(updated.email()).isEqualTo("new@example.com");
        assertThat(updated.role()).isEqualTo(Role.ADMIN);
    }

    @Test void failedIdentityUpdateDoesNotChangeUserProfile() {
        AuthProvisionClient provisioning = mock(AuthProvisionClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        UserStore store = new UserStore(mock(RevocationClient.class), provisioning, provider);
        var user = store.create("person@example.com", "First", "Last", Role.TRAVELER);
        doThrow(new IllegalStateException("auth offline")).when(provisioning)
                .update(user.id(), user.email(), "TRAVELER", false);

        assertThrows(UserStore.Conflict.class, () -> store.update(user.id(), null, null, null, null, UserDomain.Status.DISABLED));
        assertThat(store.get(user.id()).status()).isEqualTo(UserDomain.Status.ACTIVE);
    }
}
