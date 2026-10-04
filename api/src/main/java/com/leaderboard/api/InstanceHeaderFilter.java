package com.leaderboard.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Adds X-Instance so you can see which API node answered behind the load balancer. */
@Component
public class InstanceHeaderFilter extends OncePerRequestFilter {

    private final String instanceId;

    public InstanceHeaderFilter(@Value("${app.instance-id}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Instance", instanceId);
        chain.doFilter(req, res);
    }
}
