package com.vectorflow.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Stores the resolved client IP as a request attribute (read by REST controllers and GraphQL). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientIpFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "vectorflow.clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ATTRIBUTE, ClientIp.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For")));
        chain.doFilter(request, response);
    }
}
