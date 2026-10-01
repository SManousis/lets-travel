package com.travelplan.payments;

import com.travelplan.payments.PaymentDomain.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
final class PaymentDeletionService {
    private final PaymentStore store;
    private final Map<String, ProviderAdapter> adapters;

    PaymentDeletionService(PaymentStore store, List<ProviderAdapter> providers) {
        this.store = store;
        this.adapters = providers.stream().collect(Collectors.toUnmodifiableMap(ProviderAdapter::provider,
                Function.identity()));
    }

    boolean delete(UUID id, UUID key) {
        var attempt = store.beginDelete(id);
        if (!attempt.shouldDetach()) return false;
        Method method = attempt.method();
        if ("CONNECT".equals(method.failureStage()) && "INVALID_PROVIDER_REFERENCE".equals(method.failureCode())) {
            store.finishDelete(method);
            return true;
        }
        ProviderAdapter adapter = adapters.get(method.provider());
        if (adapter == null) throw new PaymentDomain.Conflict("UNSUPPORTED_PROVIDER");
        try {
            adapter.detach(method.providerReference(), key.toString());
            store.finishDelete(method);
            return true;
        } catch (ProviderAdapter.Rejected ex) {
            if ("INVALID_PROVIDER_REFERENCE".equals(ex.code)) {
                store.finishDelete(method);
                return true;
            }
            store.failDelete(method, ex.code);
            throw ex;
        } catch (ProviderAdapter.Unavailable ex) {
            store.failDelete(method, ex.code);
            throw ex;
        }
    }
}
