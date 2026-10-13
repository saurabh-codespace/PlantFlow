package com.plantflow.transport;

import java.time.Instant;

public record Trip(long id, String kind, long originId, long destinationId, String origin, String destination,
    String status, int passengers, String requestedBy, String purpose, Long vehicleId, String vehicleLabel,
    Integer capacity, String driverId, String driverName, Instant scheduledAt, Instant createdAt,
    Instant assignedAt, Instant arrivedAt, Instant startedAt, Instant completedAt, Long myBookingId, String assignmentError) {}
