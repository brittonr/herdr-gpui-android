package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RemoteHostStoreImportTest {
    private static final String PIN = "SHA256:4fFfVsbJWG6Esa7Cq5Nr4KRGFobxDXCQdn9gVkicEgA";

    private static SavedRemoteHost host(String name, String address) {
        return SavedRemoteHost.create(name, "tcp", address, 22, "", "", "", "britton", "main");
    }

    private static SavedRemoteHost trusted(SavedRemoteHost host) {
        return host.copy(host.name, PIN, host.shortcuts, host.quickCommands, null);
    }

    @Test public void importAddsOnlyHostsNotSavedYetAndKeepsTheFilesTrust() {
        SavedRemoteHost desk = host("Desk", "desk.example.org");
        // The same account again, now trusted in the file: the saved profile wins.
        SavedRemoteHost deskInFile = trusted(host("Desk at work", "desk.example.org"));
        SavedRemoteHost laptop = trusted(host("Laptop", "laptop.example.org"));
        SavedRemoteHost laptopAgain = host("Laptop copy", "laptop.example.org");
        List<SavedRemoteHost> added = RemoteHostStore.newProfiles(Collections.singletonList(desk),
                Arrays.asList(deskInFile, laptop, laptopAgain));
        assertEquals(1, added.size());
        assertEquals("Laptop", added.get(0).name);
        assertEquals(PIN, added.get(0).expectedFingerprint);
    }

    @Test public void anImportPastTheProfileLimitAddsNothing() {
        List<SavedRemoteHost> saved = new ArrayList<>();
        for (int i = 0; i < RemoteHostStore.MAX_PROFILES - 1; i++) {
            saved.add(host("Host " + i, "host" + i + ".example.org"));
        }
        // Filling the list exactly is allowed.
        assertEquals(1, RemoteHostStore.newProfiles(saved,
                Collections.singletonList(host("Last", "last.example.org"))).size());
        try {
            RemoteHostStore.newProfiles(saved, Arrays.asList(host("A", "a.example.org"), host("B", "b.example.org")));
            fail("an import past the limit was planned");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("limit of 32"));
        }
    }
}
