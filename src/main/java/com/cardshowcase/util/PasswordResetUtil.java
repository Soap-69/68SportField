package com.cardshowcase.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Standalone utility — run this to generate a fresh BCrypt hash for a password,
 * then paste the printed SQL into your database client.
 *
 * Usage (from project root):
 *   mvn compile exec:java -Dexec.mainClass="com.cardshowcase.util.PasswordResetUtil"
 *
 * Or with a custom password:
 *   mvn compile exec:java -Dexec.mainClass="com.cardshowcase.util.PasswordResetUtil" \
 *       -Dexec.args="myNewPassword"
 */
public class PasswordResetUtil {

    public static void main(String[] args) {
        String password = (args.length > 0) ? args[0] : "admin123";

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode(password);

        System.out.println();
        System.out.println("Password : " + password);
        System.out.println("BCrypt   : " + hash);
        System.out.println();
        System.out.println("-- Run this SQL to reset admin credentials:");
        System.out.println("UPDATE admin_users");
        System.out.println("SET    username = 'admin',");
        System.out.println("       password = '" + hash + "'");
        System.out.println("WHERE  id = 1;");
        System.out.println();
    }
}
