package com.travelplan.travel;
import java.time.*; import java.util.*;
public final class TravelDomain {
 public record Destination(UUID id,String name,String country,Instant createdAt) {}
 public record Stop(UUID id,UUID destinationId,int position,LocalDate arrivalDate,LocalDate departureDate) {}
 public record Activity(UUID id,String name,String description,LocalDate date) {}
 public record Accommodation(UUID id,String name,String address,LocalDate checkIn,LocalDate checkOut) {}
 public record Transport(UUID id,String mode,String departure,String arrival,LocalDate date) {}
 public record Travel(UUID id,String name,LocalDate startDate,LocalDate endDate,long durationDays,List<Stop> stops,List<Activity> activities,List<Accommodation> accommodations,List<Transport> transports,Set<UUID> travelerIds,Instant createdAt) {}
}
