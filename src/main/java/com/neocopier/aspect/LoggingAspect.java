package com.neocopier.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Pointcut("execution(* com.neocopier.controller..*.*(..))")
    public void controllerMethods() {}

    @Around("controllerMethods()")
    public Object logHttpRequestsAndResponses(ProceedingJoinPoint joinPoint) throws Throwable {
        Instant startTime = Instant.now();
        String reqTimestamp = TIMESTAMP_FORMATTER.format(startTime);

        HttpServletRequest request = getHttpServletRequest();
        String httpMethod = request != null ? request.getMethod() : "N/A";
        String requestUri = request != null ? request.getRequestURI() : "N/A";
        String queryString = (request != null && request.getQueryString() != null) ? "?" + request.getQueryString() : "";
        String clientIp = request != null ? getClientIp(request) : "127.0.0.1";

        String targetMethod = joinPoint.getSignature().getDeclaringType().getSimpleName() + "." + joinPoint.getSignature().getName();
        String argsString = formatArgs(joinPoint.getArgs());

        // 1. Log HTTP Request
        log.info("[AOP] [REQ] [{}] {} {}{} | IP: {} | Handler: {} | Args: {}",
                reqTimestamp, httpMethod, requestUri, queryString, clientIp, targetMethod, argsString);

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            Instant errTime = Instant.now();
            long durationMs = errTime.toEpochMilli() - startTime.toEpochMilli();
            String errTimestamp = TIMESTAMP_FORMATTER.format(errTime);

            log.error("[AOP] [ERR] [{}] {} {}{} | Duration: {}ms | Exception: {}",
                    errTimestamp, httpMethod, requestUri, queryString, durationMs, ex.getMessage());
            throw ex;
        }

        Instant endTime = Instant.now();
        long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        String resTimestamp = TIMESTAMP_FORMATTER.format(endTime);
        String responsePayload = formatResponsePayload(result);

        // 2. Log HTTP Response
        log.info("[AOP] [RES] [{}] {} {}{} | Duration: {}ms | Response: {}",
                resTimestamp, httpMethod, requestUri, queryString, durationMs, responsePayload);

        return result;
    }

    private HttpServletRequest getHttpServletRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            return Arrays.toString(args);
        }
    }

    private String formatResponsePayload(Object result) {
        if (result == null) return "null";
        try {
            String json = objectMapper.writeValueAsString(result);
            if (json.length() > 500) {
                return json.substring(0, 500) + "... [truncated]";
            }
            return json;
        } catch (Exception e) {
            return result.toString();
        }
    }
}
