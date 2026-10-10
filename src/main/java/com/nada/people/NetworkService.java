package com.nada.people;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-memory store of people and their mutual connections, plus the
 * shortest-path search. All public methods are synchronized so the HTTP
 * server may call them from several threads.
 */
final class NetworkService {

    /** A path may contain at most this many hops (edges). */
    static final int MAX_HOPS = 3;

    private final Map<Long, Person> people = new HashMap<>();
    /** Adjacency sets; TreeSet keeps contacts (and BFS order) deterministic. */
    private final Map<Long, Set<Long>> contacts = new HashMap<>();
    private long nextId = 1;

    synchronized Person addPerson(String name) {
        if (name == null || isBlank(name)) {
            throw new BadRequestException("name must not be empty or blank");
        }
        Person person = new Person(nextId++, name);
        people.put(person.id(), person);
        contacts.put(person.id(), new TreeSet<>());
        return person;
    }

    /** Creates a mutual connection. Repeating an existing connection is a no-op. */
    synchronized void connect(long a, long b) {
        if (a == b) {
            throw new BadRequestException("a and b must be different people");
        }
        requirePerson(a);
        requirePerson(b);
        contacts.get(a).add(b);
        contacts.get(b).add(a);
    }

    /** Direct contacts of a person, ordered by id; empty if they have none. */
    synchronized List<Person> contactsOf(long id) {
        requirePerson(id);
        List<Person> result = new ArrayList<>();
        for (long contactId : contacts.get(id)) {
            result.add(people.get(contactId));
        }
        return result;
    }

    /**
     * Shortest path from {@code from} to {@code to} using at most
     * {@link #MAX_HOPS} hops, found with breadth-first search. The path
     * includes both end points. Empty if no such path exists.
     */
    synchronized Optional<List<Person>> findPath(long from, long to) {
        Person start = requirePerson(from);
        requirePerson(to);
        if (from == to) {
            return Optional.of(List.of(start));
        }

        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Integer> hops = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        cameFrom.put(from, from);
        hops.put(from, 0);
        queue.add(from);

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int currentHops = hops.get(current);
            if (currentHops == MAX_HOPS) {
                continue; // anything found from here would exceed the limit
            }
            for (long next : contacts.get(current)) {
                if (cameFrom.containsKey(next)) {
                    continue;
                }
                cameFrom.put(next, current);
                hops.put(next, currentHops + 1);
                if (next == to) {
                    return Optional.of(buildPath(cameFrom, from, to));
                }
                queue.add(next);
            }
        }
        return Optional.empty();
    }

    private List<Person> buildPath(Map<Long, Long> cameFrom, long from, long to) {
        List<Person> path = new ArrayList<>();
        for (long id = to; id != from; id = cameFrom.get(id)) {
            path.add(people.get(id));
        }
        path.add(people.get(from));
        Collections.reverse(path);
        return path;
    }

    private Person requirePerson(long id) {
        Person person = people.get(id);
        if (person == null) {
            throw new PersonNotFoundException(id);
        }
        return person;
    }

    /** True if every character is whitespace or a space separator (e.g. NBSP, U+3000). */
    private static boolean isBlank(String s) {
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
    }
}
