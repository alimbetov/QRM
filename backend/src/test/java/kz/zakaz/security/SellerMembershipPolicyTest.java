package kz.zakaz.security;

import java.net.URI;
import java.util.UUID;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.ProductType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SellerMembershipPolicyTest {
    @Test void membershipPrincipalIsIssuerAndSubjectBoundAndHasExplicitRole() {
        var seller=UUID.randomUUID();
        var membership=SellerMembership.grant("https://idp.example.test","user-17",seller,
                SellerMembership.Role.CATALOG_EDITOR);
        assertThat(membership.getSellerId()).isEqualTo(seller);
        assertThat(membership.getIssuer()).isEqualTo("https://idp.example.test");
        assertThat(membership.getSubject()).isEqualTo("user-17");
        assertThat(membership.canEditCatalog()).isTrue();
        assertThatThrownBy(()->SellerMembership.grant("","user-17",seller,
                SellerMembership.Role.CATALOG_EDITOR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SellerMembership.grant("https://idp.example.test","",seller,
                SellerMembership.Role.CATALOG_EDITOR)).isInstanceOf(IllegalArgumentException.class);
    }
}
