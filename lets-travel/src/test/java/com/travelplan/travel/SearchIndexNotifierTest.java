package com.travelplan.travel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** search-service being down must never break travel writes or browsing. */
class SearchIndexNotifierTest {

    @Test void unreachableSearchServiceDoesNotFailTravelChanges() {
        SearchIndexNotifier notifier = new SearchIndexNotifier("http://127.0.0.1:1", "token");
        TravelStore store = new TravelStore();
        store.searchIndexNotifier(notifier);
        var destination = store.createDestination("Lisbon", "Portugal");
        LocalDate start = LocalDate.now().plusDays(20);
        var travel = store.create(UUID.randomUUID(), "Lisbon weekend", "City break", start, start.plusDays(2), 5,
                new BigDecimal("300"), "EUR", List.of(new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null)),
                List.of(), List.of(), List.of());

        assertThatCode(() -> store.publish(travel.id())).doesNotThrowAnyException();
        assertThat(store.bookableTravels()).extracting(TravelDomain.Travel::id).containsExactly(travel.id());
        notifier.stop();
    }

    @Test void blankUrlDisablesNotifications() {
        assertThatCode(() -> new SearchIndexNotifier("", "token").travelChanged(UUID.randomUUID()))
                .doesNotThrowAnyException();
    }
}
