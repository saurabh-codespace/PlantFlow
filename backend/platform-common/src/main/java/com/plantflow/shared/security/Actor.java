package com.plantflow.shared.security;

import com.plantflow.shared.ApiException;
import java.util.List;
import org.springframework.security.core.Authentication;

public record Actor(String username,String role) {
    public static Actor from(Authentication auth) {
        if(auth==null || !auth.isAuthenticated()) throw ApiException.forbidden();
        for(String role:List.of("ADMIN","DISPATCHER","DRIVER","EMPLOYEE"))
            if(auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_"+role))) return new Actor(auth.getName(),role);
        throw ApiException.forbidden();
    }
    public boolean dispatch() { return role.equals("ADMIN") || role.equals("DISPATCHER"); }
    public void requireDispatch() { if(!dispatch()) throw ApiException.forbidden(); }
    public void requireRider() { if(!dispatch() && !role.equals("EMPLOYEE")) throw ApiException.forbidden(); }
}
