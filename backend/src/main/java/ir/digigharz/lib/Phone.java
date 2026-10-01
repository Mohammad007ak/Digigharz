package ir.digigharz.lib;

import java.util.regex.Pattern;

/**
 * Normalizes Iranian mobile numbers to the 09xxxxxxxxx form, so the same person
 * matches whether they typed +98, 0098, or Persian digits.
 */
public final class Phone {
  private Phone() {}

  private static final Pattern COUNTRY = Pattern.compile("^(0098|98)(?=9\\d{9}$)");
  private static final Pattern BARE = Pattern.compile("^(?=9\\d{9}$)");
  private static final Pattern MOBILE = Pattern.compile("^09\\d{9}$");

  public static String toLatinDigits(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      if (c >= '۰' && c <= '۹') out.append((char) ('0' + (c - '۰')));
      else if (c >= '٠' && c <= '٩') out.append((char) ('0' + (c - '٠')));
      else out.append(c);
    }
    return out.toString();
  }

  public static String normalize(String input) {
    if (input == null) return null;
    String digits = toLatinDigits(input).replaceAll("\\D", "");
    String local = BARE.matcher(COUNTRY.matcher(digits).replaceFirst("")).replaceFirst("0");
    return MOBILE.matcher(local).matches() ? local : null;
  }
}
