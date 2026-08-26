package dev.nytweetdeck.list;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lists")
public class ListDirectoryController {

    private final ListDirectoryService service;

    public ListDirectoryController(ListDirectoryService service) {
        this.service = service;
    }

    @GetMapping
    public ListDirectoryPage lists(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor) {
        return service.list(accountId, scope, query, cursor);
    }
}
