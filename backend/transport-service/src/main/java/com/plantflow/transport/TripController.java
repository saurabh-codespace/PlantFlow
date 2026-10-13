package com.plantflow.transport;

import com.plantflow.shared.security.Actor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TripController {
    private final TripRepository repository;
    private final TripService service;
    public TripController(TripRepository repository,TripService service) { this.repository=repository;this.service=service; }
    @GetMapping("/trips") List<Trip> trips(Authentication auth) { return repository.list(Actor.from(auth)); }
    @PostMapping("/trips") Trip request(@Valid @RequestBody TripService.Request input,Authentication auth) { return service.request(input,Actor.from(auth)); }
    @PostMapping("/shuttles") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) Trip shuttle(@Valid @RequestBody TripService.Shuttle input,Authentication auth) { return service.shuttle(input,Actor.from(auth)); }
    @PostMapping("/trips/{id}/assign") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) Trip assign(@PathVariable long id,@Valid @RequestBody TripService.Assignment input,Authentication auth) { return service.assign(id,input,Actor.from(auth)); }
    @PostMapping("/trips/{id}/status") Trip status(@PathVariable long id,@Valid @RequestBody TripService.Status input,Authentication auth) { return service.status(id,input,Actor.from(auth)); }
    @PostMapping("/trips/{id}/reservations") Trip reserve(@PathVariable long id,@Valid @RequestBody TripService.Seats input,Authentication auth) { return service.reserve(id,input,Actor.from(auth)); }
    @DeleteMapping("/trips/{id}/reservations/me") Trip cancel(@PathVariable long id,Authentication auth) { return service.cancelReservation(id,Actor.from(auth)); }
}
