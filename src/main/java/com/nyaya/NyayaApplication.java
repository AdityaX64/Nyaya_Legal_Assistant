package com.nyaya;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Main entry point for the Nyaya BNS/BNSS RAG service
@SpringBootApplication
public class NyayaApplication {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    public static void main(String[] args) {
        SpringApplication.run(NyayaApplication.class, args);
    }
}


