package com.example.helloapi;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final BuildProperties buildProperties;
    private final String commit;

    public ApiController(BuildProperties buildProperties,
            @Value("${app.commit:unknown") String commit) {
        this.buildProperties = buildProperties;
        this.commit = commit;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "World") String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Hello, " + name + "!");
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", buildProperties.getArtifact());
        body.put("version", buildProperties.getVersion());
        body.put("buildTime", buildProperties.getTime() != null ? buildProperties.getTime().toString() : "unknown");
        body.put("commit", commit);
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("hostname", hostname());
        return body;
    }

    private static String hostname(){
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
