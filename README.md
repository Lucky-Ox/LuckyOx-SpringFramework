# LuckyOx-SpringFramework

A lightweight Spring-like framework for learning purposes.

## How to run

This repository currently matches the early `resource-resolver` stage in
`summer-framework`, so it does not have an application `main` method yet.

Use Maven to compile and run tests:

```bash
mvn test
```

Or package it:

```bash
mvn package
```

The original `summer-framework` project targets Java 21. This project is
currently configured for Java 17 because the local Maven runtime uses JDK 17 and
the current code does not require Java 21 features.

Later, when a boot/web sample is added, the startup style should follow
`summer-framework/step-by-step/hello-boot`: create a `Main` class and call
`SummerApplication.run(...)` from it.
