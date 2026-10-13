package com.plantflow.fleet;

import com.plantflow.shared.security.Actor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FleetController {
    private final FleetService service;
    public FleetController(FleetService service) { this.service = service; }
    @GetMapping("/vehicles") List<FleetService.Vehicle> vehicles() { return service.vehicles(); }
    @GetMapping("/drivers") List<FleetService.Driver> drivers(Authentication auth) { return service.drivers(Actor.from(auth)); }
    @PostMapping("/vehicles") FleetService.Vehicle create(@Valid @RequestBody FleetService.NewVehicle request, Authentication auth) { return service.create(request, Actor.from(auth)); }
    @PostMapping("/drivers") FleetService.Driver createDriver(@Valid @RequestBody FleetService.NewDriver request, Authentication auth) { return service.createDriver(request, Actor.from(auth)); }
    @PatchMapping("/vehicles/{id}/status") FleetService.Vehicle status(@PathVariable long id, @Valid @RequestBody FleetService.VehicleStatus request, Authentication auth) { return service.status(id, request, Actor.from(auth)); }
}
