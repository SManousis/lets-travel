package com.travelplan.travel;
import static org.junit.jupiter.api.Assertions.*; import java.time.*; import java.util.*; import org.junit.jupiter.api.Test;
class TravelStoreTest {
 @Test void durationIncludesBothDatesAndSharedDestinationCannotBeDeleted(){var s=new TravelStore();var d=s.createDestination("Athens","GR");var t=s.save("Trip",LocalDate.of(2026,1,1),LocalDate.of(2026,1,3),List.of(new TravelDomain.Stop(UUID.randomUUID(),d.id(),0,null,null)),List.of(),List.of(),List.of());assertEquals(3,t.durationDays());assertThrows(TravelStore.Conflict.class,()->s.deleteDestination(d.id()));s.delete(t.id());s.deleteDestination(d.id());}
 @Test void reversedDatesAndMissingStopsAreRejected(){var s=new TravelStore();var d=s.createDestination("Rome","IT");var stop=new TravelDomain.Stop(UUID.randomUUID(),d.id(),0,null,null);assertThrows(TravelStore.Conflict.class,()->s.save("bad",LocalDate.of(2026,2,2),LocalDate.of(2026,2,1),List.of(stop),List.of(),List.of(),List.of()));assertThrows(TravelStore.Conflict.class,()->s.save("bad",LocalDate.now(),LocalDate.now(),List.of(),List.of(),List.of(),List.of()));}
}
