package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.Assert.*;

public class AtmsBinderSignatureResolverTest {
    private static String[] aospSignature() {
        return new String[]{"android.app.IApplicationThread", "java.lang.String", "java.lang.String",
                "android.content.Intent", "java.lang.String", "android.os.IBinder", "java.lang.String",
                "int", "int", "android.app.ProfilerInfo", "android.os.Bundle", "int"};
    }

    @Test public void acceptsVerifiedAosp14Through16PublicBinderShape() {
        assertTrue(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PUBLIC,
                "int", aospSignature()));
        assertTrue(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PUBLIC | Modifier.FINAL,
                "int", aospSignature()));
        assertEquals("android.content.Intent", aospSignature()[AtmsBinderSignatureResolver.INTENT_INDEX]);
        assertEquals("int", aospSignature()[AtmsBinderSignatureResolver.USER_ID_INDEX]);
        assertEquals("android.os.IBinder", aospSignature()[AtmsBinderSignatureResolver.RESULT_TO_INDEX]);
    }

    @Test public void refusesPrivateInternalOverloadAndSynchronizedOemVariant() {
        String[] internal = Arrays.copyOf(aospSignature(), 13);
        internal[12] = "boolean";
        assertFalse(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PRIVATE,
                "int", internal));
        assertFalse(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PUBLIC | Modifier.SYNCHRONIZED,
                "int", aospSignature()));
        assertFalse(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PUBLIC | Modifier.STATIC,
                "int", aospSignature()));
    }

    @Test public void changedParameterPositionOrTypeFailsClosed() {
        String[] shifted = aospSignature();
        shifted[2] = "android.content.Intent";
        shifted[3] = "java.lang.String";
        assertFalse(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PUBLIC, "int", shifted));
        String[] longUser = aospSignature();
        longUser[11] = "long";
        assertFalse(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PUBLIC, "int", longUser));
        assertFalse(AtmsBinderSignatureResolver.matches("startActivity", Modifier.PUBLIC, "int", aospSignature()));
        assertFalse(AtmsBinderSignatureResolver.matches("startActivityAsUser", Modifier.PUBLIC, "void", aospSignature()));
    }
}
