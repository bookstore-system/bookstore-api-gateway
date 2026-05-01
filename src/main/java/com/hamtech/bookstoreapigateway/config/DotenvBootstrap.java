package com.hamtech.bookstoreapigateway.config;

import io.github.cdimascio.dotenv.Dotenv;

public final class DotenvBootstrap {

    private DotenvBootstrap() {}

    public static void loadDotenvToSystemProperties() {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        dotenv.entries().forEach(e -> {
            if (System.getProperty(e.getKey()) == null) {
                System.setProperty(e.getKey(), e.getValue());
            }
        });
    }
}

