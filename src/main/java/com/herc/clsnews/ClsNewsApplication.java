package com.herc.clsnews;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClsNewsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClsNewsApplication.class, args);
    }
}
