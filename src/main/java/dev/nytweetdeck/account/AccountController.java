package dev.nytweetdeck.account;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountStore accountStore;

    public AccountController(AccountStore accountStore) {
        this.accountStore = accountStore;
    }

    @GetMapping
    public List<AccountStore.AccountSummary> accounts() {
        return accountStore.accountSummaries();
    }
}
