package kz.zakaz.publicqr;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LeadReadAuditRepository extends JpaRepository<LeadReadAudit,UUID>{}
