package kz.zakaz.order;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;
public final class OrderNumberGenerator {
 public static final String ALPHABET="23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
 private final RandomGenerator entropy;
 public OrderNumberGenerator() { this(new SecureRandom()); }
 OrderNumberGenerator(RandomGenerator entropy) { this.entropy=Objects.requireNonNull(entropy); }
 public String generate() {
  StringBuilder s=new StringBuilder("ZK-");
  for(int i=0;i<8;i++){if(i==4)s.append('-');s.append(ALPHABET.charAt(entropy.nextInt(ALPHABET.length())));}
  return s.toString();
 }
 public static boolean isValid(String value) {
  if(value==null || value.length()!=12 || !value.startsWith("ZK-") || value.charAt(7)!='-')return false;
  for(int i=3;i<12;i++)if(i!=7 && ALPHABET.indexOf(value.charAt(i))<0)return false;
  return true;
 }
}