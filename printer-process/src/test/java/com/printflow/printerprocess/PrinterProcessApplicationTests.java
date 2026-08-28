package com.printflow.printerprocess;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "printer.process.runner.enabled=false")
class PrinterProcessApplicationTests {

    @Test
    void contextLoads() {
    }

}
