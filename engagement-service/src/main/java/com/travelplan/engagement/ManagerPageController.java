package com.travelplan.engagement;

import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ManagerPageController {
    private final ManagerPageService pages;

    ManagerPageController(ManagerPageService pages) { this.pages = pages; }

    @GetMapping("/api/managers/{managerId}")
    ManagerPageService.ManagerPage manager(@PathVariable UUID managerId) { return pages.page(managerId); }

    @ExceptionHandler(ManagerPageService.ManagerNotFound.class)
    ResponseEntity<Error> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("NOT_FOUND", "Manager not found"));
    }

    @ExceptionHandler({ManagerPageService.DependencyUnavailable.class, DataAccessException.class})
    ResponseEntity<Error> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error("DEPENDENCY_UNAVAILABLE", "Required service unavailable"));
    }

    record Error(String code, String message) {}
}
