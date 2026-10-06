package ru.seoniks.auth.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.seoniks.auth.domain.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    @Query("select u from User u left join fetch u.pages where u.id = :id")
    Optional<User> findWithPagesById(@Param("id") UUID id);

    @Query("select distinct u from User u left join fetch u.pages order by u.email")
    List<User> findAllWithPages();
}