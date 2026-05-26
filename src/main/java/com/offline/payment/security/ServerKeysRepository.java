package com.offline.payment.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ServerKeysRepository extends JpaRepository<ServerKeys, String> {
}
