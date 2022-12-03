package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class WorkshopImplemented implements Workshop {
    // Number of available entries
    private final long maxEntries;
    // Read-only map of wrapped workplaces
    private final ConcurrentHashMap<WorkplaceId, WorkplaceWrapper> availableWorkplaces = new ConcurrentHashMap<>();
    // Counter of possible number of entries to satisfy 2*N rule: <ThreadId, leftEntries>
    // TODOD serves as a queue
    private final LinkedHashMap<Long, Long> entryCounter = new LinkedHashMap<>();

    /* Every entry is changed only by a single thread, whose id is the key in a map */
    // Actual workplace a given thread is seated at: <ThreadId, WorkplaceId>
    private final ConcurrentHashMap<Long, WorkplaceId> actualWorkplace = new ConcurrentHashMap<>();
    // Actual workplace a given thread is seated at: <ThreadId, WorkplaceId>
    private final ConcurrentHashMap<Long, WorkplaceId> previousWorkplace = new ConcurrentHashMap<>();
    // Enables distinction between entering and switching to users; in ConcurrentHashMap it is impossible
    // to put null as a value or key, therefore checking condition previousWorkplace == null
    // for entering users throws a NullPointerException
    private final ConcurrentHashMap<Long, Boolean> hasJustEntered = new ConcurrentHashMap<>();

    /* Workplace data */
    // Indicates whether the user can seat at the given workplace
    private final ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToSeatAt = new ConcurrentHashMap<>();
    // Idicates whether the user can start using (call use()) at the given workplace
    private final ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToUse = new ConcurrentHashMap<>();

    /* Synchronization of the counter of possible number of entries to satisfy 2*N rule */
    private final Semaphore mutexWaitForASeatAndEntryCounter = new Semaphore(1);
//    private Long howManyWaitForEntry = 0L;
//    private Semaphore waitForEntry = new Semaphore(0, true); // FIFO semaphore
    // Entry semaphores
    // private final ArrayDeque<Semaphore> waitForEntry = new ArrayDeque<>();
    private final LinkedHashMap<Long, Semaphore> waitForEntry = new LinkedHashMap<>();

    /* Synchronization of the access to the workplace data */
    // private Semaphore mutexWorkplaceData = new Semaphore(1);
    // private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWorkplaceData = new ConcurrentHashMap<>();

    /* Synchronization of the access to the seat at the given workplace
    *  Mutex protects also workplace data isAvailableToSeat
    * */
    private final Semaphore mutexWaitForSeat = new Semaphore(1);
    private long howManyWaitForSeat = 0;
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitForASeatAndEntry = new ConcurrentHashMap<>();
    // For switchTo()
    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitForASeat = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, Semaphore> waitForSeat = new ConcurrentHashMap<>();

    /* Synchronization of the permission to use (call use()) the given workplace
    *  Mutex protects also workplace data isAvailableToSeat
    * */
    // private Semaphore mutexWaitToUse = new Semaphore(1);
    // private long howManyWaitToUse = 0;
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitToUse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, Semaphore> waitToUse = new ConcurrentHashMap<>();



    private void createAvailableWorkplaceHashmap(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            availableWorkplaces.putIfAbsent(place.getId(),
                    new WorkplaceWrapper(place.getId(), place,
                            entryCounter,
                            actualWorkplace,
                            previousWorkplace,
                            hasJustEntered,
                            isAvailableToSeatAt,
                            isAvailableToUse,
                            mutexWaitForASeatAndEntryCounter,
                            waitForEntry,
//                                ConcurrentHashMap<WorkplaceId, Long> howManyWaitForASeat,
//                                ConcurrentHashMap<WorkplaceId, Semaphore> waitForSeat,
                            mutexWaitToUse,
                            howManyWaitToUse,
                            waitToUse));
        }
    }

    private void initializeWorkplaceData(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            isAvailableToSeatAt.putIfAbsent(place.getId(), true);
            isAvailableToUse.putIfAbsent(place.getId(), true);
        }
    }

    private void initializationOfSemaphoreMaps(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            WorkplaceId placeId = place.getId();
            waitForSeat.putIfAbsent(placeId, new Semaphore(0, true));
            waitToUse.putIfAbsent(placeId, new Semaphore(0, true));

            howManyWaitForASeat.putIfAbsent(placeId, 0L);
            howManyWaitToUse.putIfAbsent(placeId, 0L);

            mutexWaitToUse.putIfAbsent(placeId, new Semaphore(1, true));
//            mutexWaitForASeatAndEntry.putIfAbsent(placeId, new Semaphore(1, true));


        }
    }

    private void constructorDataInitialization(Collection<Workplace> workplaces) {
        createAvailableWorkplaceHashmap(workplaces);
        initializeWorkplaceData(workplaces);
        initializationOfSemaphoreMaps(workplaces);
    }

    public WorkshopImplemented(Collection<Workplace> workplaces) {
        this.maxEntries = 2L *workplaces.size(); //TODO experiment
        constructorDataInitialization(workplaces);

        // System.out.println("maxEntres: " + this.maxEntries + " doubled: " + 2*this.maxEntries);

        // FIXME remove
        printout();
    }

    // Only one thread will add a given entry - the thread of the given key id
    private void putActualAndPreviousWorkplace(WorkplaceId actual, WorkplaceId previous) {
        long currentThreadId = Thread.currentThread().getId();
        WorkplaceId beenActualPutBefore = actualWorkplace.putIfAbsent(currentThreadId, actual);
        if (beenActualPutBefore != null) { // returns null if the key has NOT been mapped before
            actualWorkplace.replace(currentThreadId, actual);
        }

        WorkplaceId beenPreviousPutBefore = previousWorkplace.putIfAbsent(currentThreadId, previous);
        if (beenPreviousPutBefore != null) {
            previousWorkplace.replace(currentThreadId, previous);
        }
    }

    @Override
    public Workplace enter(WorkplaceId wid) {
        Long currentThreadId = Thread.currentThread().getId();
        hasJustEntered.put(currentThreadId, true);
        putActualAndPreviousWorkplace(wid, wid);
        entryCounter.put(currentThreadId, maxEntries);

        // Check whether entry is possible
        try {
            mutexWaitForASeatAndEntryCounter.acquire();

            Iterator<Long> firstElement = entryCounter.keySet().iterator();
            if ((!firstElement.hasNext() && entryCounter.get(firstElement.next()) == 0)
                || !isAvailableToSeatAt.get(wid)) {
                // System.out.println(Thread.currentThread().getName() + " No entries");
                    Semaphore meWaitingForEntry = new Semaphore(0);
                    waitForEntry.put(currentThreadId, meWaitingForEntry);
                    mutexWaitForASeatAndEntryCounter.release();

                    // The reference is remembered and the semaphore is pushed in the correct order
                    meWaitingForEntry.acquire();
            }

            Iterator<Long> iterateOverQueue = entryCounter.keySet().iterator();
            Long keyVal;

            // Decrease counter values up to our key
            while (iterateOverQueue.hasNext() && !(keyVal = iterateOverQueue.next()).equals(currentThreadId)) { // TODO TEST this
                entryCounter.put(keyVal, entryCounter.get(keyVal) - 1);
            }

            // entrySet contains at least one key - ours, so remove() will delete the last returned key
            // iterateOverQueue.remove(); // TODO uncomment double removal?

            // entryCounter.putIfAbsent(currentThreadId, maxEntries); // TODO moved here
            // Indicate that the seat will be occupied
            isAvailableToSeatAt.replace(wid, false);

            // entryCounter.remove(currentThreadId); //TODO changed; test iterator.remove

            mutexWaitForASeatAndEntryCounter.release();

            return availableWorkplaces.get(wid);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        try {
            // System.out.println(Thread.currentThread().getName() + " SWITCH acquire entry");
            mutexWaitForASeatAndEntryCounter.acquire();

            Long currentThreadId = Thread.currentThread().getId();

            // System.out.println(Thread.currentThread().getName() + " SWITCH entry acquired");

            // entryCounter.put(Thread.currentThread().getId(), maxEntries);
            entryCounter.put(currentThreadId, maxEntries);

            // mutexWaitForASeatAndEntryCounter.release(); // TODO add
//        } catch (InterruptedException e) {
//            throw new RuntimeException("panic: unexpected thread interruption");
//        }

            // Earlier assignment would require non-atomic retrieval of the value for getId()
            // and non-atomic assignment, then the assigned value would be used for putting the counter.
            // Retrieval without preceding assignment is slightly faster and indicates the demand
            // for switching as soon as it is possible.

            // Only current thread retrieves these values, but concurrent hashmap enables thread-safe
            // access for multiple threads
            WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);

            // wid is an ID of the workplace I'm going to change to
            // I have NOT changed that workplace yet
            if (myActualWorkplace != wid) { // TODO changed from my previous workplace

                // Updates information about my previous workplace

                // If another user wants to visit my previous workplace
//                boolean areUsersWaitingForPrevious = howManyWaitForASeat.get(myActualWorkplace) > 0;
                if (howManyWaitForASeat.get(myActualWorkplace) > 0) {
                    // tagged as occupied
                    Semaphore previousWorkplace = waitForSeat.get(myActualWorkplace);
                    howManyWaitForASeat.compute(myActualWorkplace, (key, val) -> --val);
                    previousWorkplace.release(); // It will be released in B part, from where it does not need sensitive local variables
//                if (areUsersWaitingForPrevious) {
                    // The workplace is tagged as occupied
//                    howManyWaitForASeat.compute(myActualWorkplace, (key, val) -> --val);
                } else {
                    isAvailableToSeatAt.replace(myActualWorkplace, true);
                }

                /* B */
                if (!isAvailableToSeatAt.get(wid)) {
                    howManyWaitForASeat.compute(wid, (key, val) -> ++val); // TODO does it work?
                    Semaphore myDemandedSeatSemaphore = waitForSeat.get(wid);
                    mutexWaitForASeatAndEntryCounter.release();

                    // System.out.println(Thread.currentThread().getName() + "Trying to SEAT SWITCH");
                    myDemandedSeatSemaphore.acquire();

                    // Only one thread will be released and only this one will change that value.
                    // Concurrent access is safe for ConcurrentHashMap
                    // howManyWaitForASeat.compute(wid, (key, val) -> --val); // TODO is it safe?
                }
                else {
                    // Now the user is going to seat at and then perform use()
                    isAvailableToSeatAt.replace(wid, false);

                    mutexWaitForASeatAndEntryCounter.release();
                }

                // Unique threads will change values at their associated keys,
                // therefore in ConcurrentHashMap concurrent access is thread-safe
                previousWorkplace.replace(currentThreadId, myActualWorkplace);
                actualWorkplace.replace(currentThreadId, wid);
            }

            return availableWorkplaces.get(wid);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }


        // TODO Moved from wid != actual
        // Update the seat, because the user is guaranteed to enter the demanded workplace
        // putActualAndPreviousWorkplace(wid, myActualWorkplace);
        // // System.out.println(Thread.currentThread().getName() + " " + isAvailableToSeatAt.get(myPreviousWorkplace) + " " + myPreviousWorkplace);
       // return null;
    }

    @Override
    public void leave() {
        // System.out.println("LEAVING " + Thread.currentThread().getName());
        // After use from actual workplace
        Long currentThreadId = Thread.currentThread().getId();
        WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);

        // System.out.println("LEAVING actual workplace");
        Semaphore mutexActualWorkplace = mutexWaitForASeatAndEntry.get(myActualWorkplace);


        try {
            mutexActualWorkplace.acquire();
            // System.out.println("LEAVING actual workplace acquired");

            // It is impossible to use without entering, but now it is available for usage
            isAvailableToUse.replace(myActualWorkplace, true);
            // System.out.println("Allowed to use");

            // Others are allowed for entering
            if (howManyWaitForASeat.get(myActualWorkplace) > 0) {
                waitForSeat.get(myActualWorkplace).release();
            }
            else {
                isAvailableToSeatAt.replace(myActualWorkplace, true);
                mutexActualWorkplace.release();
            }
            // System.out.println("Allowed for entering");

            actualWorkplace.remove(currentThreadId);
            previousWorkplace.remove(currentThreadId);
            // System.out.println("Removed id");
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }


    }

    //TODO remove
    public void printout() {
        availableWorkplaces.forEachValue(Long.MAX_VALUE, (w)-> {
            // System.out.println(w.getIdName());
        });
        // System.out.println("decorated");
    }
}
