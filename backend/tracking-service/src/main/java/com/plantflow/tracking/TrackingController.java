package com.plantflow.tracking;

import com.plantflow.shared.ApiException;
import com.plantflow.shared.security.Actor;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tracking")
public class TrackingController {
    private final TrackingService service;
    private final CopyOnWriteArrayList<Subscriber> subscribers=new CopyOnWriteArrayList<>();
    record Subscriber(Actor actor,Instant expires,SseEmitter emitter) {}
    public TrackingController(TrackingService service) { this.service=service; }
    @GetMapping("/positions") public List<TrackingService.Position> positions(Authentication auth) { return service.positions(Actor.from(auth)); }
    @PostMapping("/trips/{id}/positions") public TrackingService.Position update(@PathVariable long id,@Valid @RequestBody TrackingService.Update input,Authentication auth) { return service.update(id,input,Actor.from(auth)); }
    @DeleteMapping("/trips/{id}/positions/me") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void stop(@PathVariable long id,Authentication auth) { service.stop(id,Actor.from(auth)); }
    @PostMapping("/device/positions") public TrackingService.Position device(@Valid @RequestBody TrackingService.Update input,@AuthenticationPrincipal Jwt jwt) {
        Object claim=jwt.getClaims().get("vehicle_id");
        long vehicle;
        try { vehicle=Long.parseLong(String.valueOf(claim)); } catch(NumberFormatException e) { throw ApiException.forbidden(); }
        if(vehicle<=0) throw ApiException.forbidden();
        return service.device(vehicle,input);
    }
    @GetMapping(value="/stream",produces="text/event-stream") public SseEmitter stream(Authentication auth,@AuthenticationPrincipal Jwt jwt) {
        var actor=Actor.from(auth);var emitter=new SseEmitter(60000L);
        if(subscribers.stream().filter(s->s.actor().username().equals(actor.username())).count()>=5) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Close another live map before opening a new one.");
        var sub=new Subscriber(actor,jwt.getExpiresAt(),emitter);subscribers.add(sub);
        emitter.onCompletion(()->subscribers.remove(sub));emitter.onTimeout(()->subscribers.remove(sub));emitter.onError(e->subscribers.remove(sub));
        return emitter;
    }
    @Scheduled(fixedDelay=2000)
    public void broadcast() {
        for(var sub:subscribers) {
            try {
                if(sub.expires()==null||!sub.expires().isAfter(Instant.now())) { sub.emitter().complete();subscribers.remove(sub);continue; }
                for(var position:service.positions(sub.actor())) sub.emitter().send(SseEmitter.event().name("position").data(position));
                sub.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch(Exception e) { sub.emitter().complete();subscribers.remove(sub); }
        }
    }
}
