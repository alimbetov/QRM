package kz.zakaz.deal;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SellerBidQuoteRepository extends JpaRepository<SellerBidQuoteSnapshot,UUID> {}
