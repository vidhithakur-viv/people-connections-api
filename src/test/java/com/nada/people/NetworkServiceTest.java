package com.nada.people;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetworkServiceTest {

    private NetworkService service;

    @BeforeEach
    void setUp() {
        service = new NetworkService();
    }

    /** Creates people named after the given names and links consecutive ones: a-b-c-... */
    private long[] chain(String... names) {
        long[] ids = new long[names.length];
        for (int i = 0; i < names.length; i++) {
            ids[i] = service.addPerson(names[i]).id();
            if (i > 0) {
                service.connect(ids[i - 1], ids[i]);
            }
        }
        return ids;
    }

    private static List<Long> ids(List<Person> people) {
        return people.stream().map(Person::id).toList();
    }

    // ---- path: the three-hop limit ----

    @Test
    void pathOfExactlyThreeHopsIsConnected() {
        long[] n = chain("A", "B", "C", "D");

        Optional<List<Person>> path = service.findPath(n[0], n[3]);

        assertTrue(path.isPresent());
        assertEquals(3, path.get().size() - 1);
        assertEquals(List.of(n[0], n[1], n[2], n[3]), ids(path.get()));
    }

    @Test
    void pathOfFourHopsIsNotConnected() {
        long[] n = chain("A", "B", "C", "D", "E");

        assertTrue(service.findPath(n[0], n[4]).isEmpty());
        assertTrue(service.findPath(n[4], n[0]).isEmpty(), "limit applies in both directions");
    }

    @Test
    void threeHopLimitIsMeasuredFromTheEndpointsNotTheWholeChain() {
        long[] n = chain("A", "B", "C", "D", "E");

        assertTrue(service.findPath(n[1], n[4]).isPresent()); // B to E: 3 hops
        assertTrue(service.findPath(n[4], n[1]).isPresent()); // E to B: 3 hops
    }

    // ---- path: other behaviour ----

    @Test
    void pathToSelfHasZeroHopsAndContainsOnlyThatPerson() {
        long a = service.addPerson("Asha").id();

        Optional<List<Person>> path = service.findPath(a, a);

        assertEquals(List.of(new Person(a, "Asha")), path.orElseThrow());
    }

    @Test
    void shortestPathWinsOverLongerOne() {
        long[] n = chain("A", "B", "C", "D");
        service.connect(n[0], n[3]); // shortcut A-D

        assertEquals(List.of(n[0], n[3]), ids(service.findPath(n[0], n[3]).orElseThrow()));
    }

    @Test
    void shortestPathIsFoundInGraphWithCycles() {
        // 1-2-3-4-5 ring (5 connects back to 1): 1 to 4 is 2 hops via 5, not 3 via 2,3
        long[] n = chain("P1", "P2", "P3", "P4", "P5");
        service.connect(n[4], n[0]);

        assertEquals(List.of(n[0], n[4], n[3]), ids(service.findPath(n[0], n[3]).orElseThrow()));
    }

    @Test
    void peopleInDifferentComponentsAreNotConnected() {
        long a = service.addPerson("A").id();
        long b = service.addPerson("B").id();

        assertTrue(service.findPath(a, b).isEmpty());
    }

    @Test
    void pathWithUnknownPersonIsNotFound() {
        long a = service.addPerson("A").id();

        assertThrows(PersonNotFoundException.class, () -> service.findPath(a, 99));
        assertThrows(PersonNotFoundException.class, () -> service.findPath(99, a));
        assertThrows(PersonNotFoundException.class, () -> service.findPath(99, 99));
    }

    // ---- people ----

    @Test
    void idsAreAssignedSequentiallyFromOne() {
        assertEquals(1, service.addPerson("A").id());
        assertEquals(2, service.addPerson("B").id());
    }

    @Test
    void namesAreStoredExactlyAsSupplied() {
        String hindi = "आशा शर्मा";
        String padded = "  Asha \t";

        assertEquals(hindi, service.addPerson(hindi).name());
        assertEquals(padded, service.addPerson(padded).name());
    }

    @Test
    void blankNamesAreRejected() {
        for (String bad : new String[] {"", " ", "   \t\n", "\u00A0", "\u3000"}) {
            assertThrows(BadRequestException.class, () -> service.addPerson(bad), "name: [" + bad + "]");
        }
        assertThrows(BadRequestException.class, () -> service.addPerson(null));
    }

    @Test
    void rejectedNameDoesNotConsumeAnId() {
        assertThrows(BadRequestException.class, () -> service.addPerson(" "));
        assertEquals(1, service.addPerson("A").id());
    }

    // ---- connections and contacts ----

    @Test
    void connectionsAreMutual() {
        long a = service.addPerson("A").id();
        long b = service.addPerson("B").id();

        service.connect(a, b);

        assertEquals(List.of(b), ids(service.contactsOf(a)));
        assertEquals(List.of(a), ids(service.contactsOf(b)));
    }

    @Test
    void repeatingAConnectionInEitherDirectionIsHarmless() {
        long a = service.addPerson("A").id();
        long b = service.addPerson("B").id();

        service.connect(a, b);
        service.connect(a, b);
        service.connect(b, a);

        assertEquals(1, service.contactsOf(a).size());
        assertEquals(1, service.contactsOf(b).size());
    }

    @Test
    void contactsAreEmptyForPersonWithNoConnections() {
        long a = service.addPerson("A").id();

        assertTrue(service.contactsOf(a).isEmpty());
    }

    @Test
    void contactsAreOrderedById() {
        long a = service.addPerson("A").id();
        long b = service.addPerson("B").id();
        long c = service.addPerson("C").id();
        long d = service.addPerson("D").id();
        service.connect(a, d);
        service.connect(a, b);
        service.connect(a, c);

        assertEquals(List.of(b, c, d), ids(service.contactsOf(a)));
    }

    @Test
    void connectingPersonToThemselvesIsRejected() {
        long a = service.addPerson("A").id();

        assertThrows(BadRequestException.class, () -> service.connect(a, a));
        assertTrue(service.contactsOf(a).isEmpty());
    }

    @Test
    void connectingUnknownPeopleIsNotFound() {
        long a = service.addPerson("A").id();

        PersonNotFoundException e = assertThrows(PersonNotFoundException.class, () -> service.connect(a, 9));
        assertEquals("no person 9", e.getMessage());
        assertThrows(PersonNotFoundException.class, () -> service.connect(9, a));
        assertTrue(service.contactsOf(a).isEmpty(), "failed connect must not leave partial state");
    }

    @Test
    void contactsOfUnknownPersonIsNotFound() {
        assertThrows(PersonNotFoundException.class, () -> service.contactsOf(9));
    }

    @Test
    void unconnectedPersonIsNotInAnotherPersonsContacts() {
        long a = service.addPerson("A").id();
        long b = service.addPerson("B").id();
        service.addPerson("C");
        service.connect(a, b);

        assertFalse(ids(service.contactsOf(a)).contains(3L));
    }

    // ---- thread safety ----

    @Test
    void concurrentCreatesGiveUniqueSequentialIds() throws Exception {
        int count = 500;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String name = "P" + i;
                futures.add(pool.submit(() -> service.addPerson(name).id()));
            }
            Set<Long> ids = new TreeSet<>();
            for (Future<Long> future : futures) {
                ids.add(future.get());
            }
            assertEquals(count, ids.size());
            assertEquals(1L, ids.iterator().next());
            assertEquals((long) count, ((TreeSet<Long>) ids).last());
        } finally {
            pool.shutdownNow();
        }
    }
}
