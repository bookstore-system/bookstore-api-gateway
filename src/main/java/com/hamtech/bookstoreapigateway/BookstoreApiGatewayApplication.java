package com.hamtech.bookstoreapigateway;

import com.hamtech.bookstoreapigateway.config.DotenvBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BookstoreApiGatewayApplication {

    public static void main(String[] args) {
        DotenvBootstrap.loadDotenvToSystemProperties();
        SpringApplication.run(BookstoreApiGatewayApplication.class, args);
    }

}
