package com.travelplan.auth.security;

import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IdentitySessionCoordinator {
    private final TransactionTemplate transactions;
    private final Object memoryLock = new Object();

    @Autowired
    public IdentitySessionCoordinator(ObjectProvider<PlatformTransactionManager> provider) {
        this(provider.getIfAvailable());
    }

    public IdentitySessionCoordinator(PlatformTransactionManager manager) {
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
    }

    public <T> T execute(Supplier<T> work) {
        if (transactions != null) return transactions.execute(status -> work.get());
        synchronized (memoryLock) { return work.get(); }
    }
}
