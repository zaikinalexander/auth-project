package ru.seoniks.auth.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.seoniks.auth.domain.Page;

import java.util.Collection;
import java.util.List;

public interface PageRepository extends JpaRepository<Page, Long> {

    List<Page> findByCodeIn(Collection<String> codes);
}