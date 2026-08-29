package com.printflow.server.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminWebController {

    // Forward the /admin root to the SPA index so direct navigations work
    @GetMapping({"/admin", "/admin/"})
    public String adminIndex() {
        return "forward:/admin/index.html";
    }

    @GetMapping({"/admin/stats", "/admin/stats/"})
    public String statsPage() {
        return "forward:/admin/stats.html";
    }
}
