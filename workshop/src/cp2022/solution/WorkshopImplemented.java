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
    private final HashMap<Long, Long> entryCounter = new HashMap<>();

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
    private Semaphore mutexEntryCounter = new Semaphore(1);
//    private Long howManyWaitForEntry = 0L;
//    private Semaphore waitForEntry = new Semaphore(0, true); // FIFO semaphore
    private final ArrayDeque<Semaphore> waitForEntry = new ArrayDeque<>();

    /* Synchronization of the access to the workplace data */
    // private Semaphore mutexWorkplaceData = new Semaphore(1);
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWorkplaceData = new ConcurrentHashMap<>();

    /* Synchronization of the access to the seat at the given workplace
    *  Mutex protects also workplace data isAvailableToSeat
    * */
    // private Semaphore mutexWaitForSeat = new Semaphore(1);
    // private long howManyWaitForSeat = 0;
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitForASeat = new ConcurrentHashMap<>();
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
            isAvailableToUse,
            mutexEntryCounter,
            waitForEntry,
            mutexWorkplaceData,
            mutexWaitForASeat,
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

            mutexWaitToUse.putIfAbsent(placeId, new Semaphore(1));
            mutexWaitForASeat.putIfAbsent(placeId, new Semaphore(1));


        }
    }

    private void constructorDataInitialization(Collection<Workplace> workplaces) {
        createAvailableWorkplaceHashmap(workplaces);
        initializeWorkplaceData(workplaces);
        initializationOfSemaphoreMaps(workplaces);
    }

    public WorkshopImplemented(Collection<Workplace> workplaces) {
        constructorDataInitialization(workplaces);
        this.maxEntries = workplaces.size();

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

        // Check whether entry is possible
        try {
            mutexEntryCounter.acquire();

            entryCounter.putIfAbsent(currentThreadId, 2*maxEntries); // TODO move before mutex?
            long minimumPossibleEntries = Collections.min(entryCounter.values());
            if (minimumPossibleEntries == 0) {
                Semaphore meWaitingForEntry = new Semaphore(0);
                waitForEntry.add(meWaitingForEntry);
                mutexEntryCounter.release();

                // The reference is remembered and the semaphore is pushed in the correct order
                meWaitingForEntry.acquire();

                // TODO I'm trying to keep the order, because otherwise the later would enter first
                if (Collections.min(entryCounter.values()) > 0 && !waitForEntry.isEmpty()) {
                    Semaphore firstInQueue = waitForEntry.remove();
                    // TODO fix mutex sharing
                    firstInQueue.release();
                }
                else {
                    mutexEntryCounter.release();
                }
            }
            else {
                mutexEntryCounter.release();
            }

            // mutexEntryCounter.release();
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }

        // When waken up after waiting for possibility to entry, the user has to wait for seat.
        // However, before completing that, no more than 2*N threads can enter,
        // therefore entryCounter is not updated upon the given thread entry completion,
        // confirmed at the beginning of decorative use() implementation.
        try {
            Semaphore mutexMyWorkplace = mutexWaitForASeat.get(wid);

            // We need concurrent map to safely access particular workshop data,
            // but mutex is used to synchronize related operations
            mutexMyWorkplace.acquire();
            if (!isAvailableToSeatAt.get(wid)) {
                howManyWaitForASeat.compute(wid, (key, val) -> ++val); // TODO does it work?
                Semaphore mySeatSemaphore = waitForSeat.get(wid);
                mutexMyWorkplace.release();

                mySeatSemaphore.acquire();

                howManyWaitForASeat.compute(wid, (key, val) -> --val);
            }

            isAvailableToSeatAt.replace(wid, false); // The current user is entering and later will call use()
            mutexMyWorkplace.release();

            return availableWorkplaces.get(wid);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }

        // return null;
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        entryCounter.putIfAbsent(Thread.currentThread().getId(), 2*maxEntries);

        // Earlier assignment would require non-atomic retrieval of the value for getId()
        // and non-atomic assignment, then the assigned value would be used for putting the counter.
        // Retrieval without preceding assignment is slightly faster and indicates the demand
        // for switching as soon as it is possible.
        Long currentThreadId = Thread.currentThread().getId();

        // Only current thread retrieves these values, but concurrent hashmap enables thread-safe
        // access for multiple threads
        WorkplaceId myPreviousWorkplace = previousWorkplace.get(currentThreadId);
        WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);

        // wid is an ID of the workplace I'm going to change to
        if (myActualWorkplace != wid) { // TODO changed from my previous workplace
            Semaphore mutexMyPreviousWorkplace = mutexWaitForASeat.get(myPreviousWorkplace);

            // Updates information about my previous workplace
            try {
                mutexMyPreviousWorkplace.acquire();
                // The user has not changed the workplace yet
                isAvailableToUse.replace(myPreviousWorkplace, false); //TODO look at it, may be dangerous
                //TODO is mutex needed? is it needed at all?

                // If another user wants to visit my previous workplace
                if (howManyWaitForASeat.get(myPreviousWorkplace) > 0) {
                    waitForSeat.get(myPreviousWorkplace).release();
                }
                else {
                    isAvailableToSeatAt.replace(myPreviousWorkplace, true);
                    mutexMyPreviousWorkplace.release();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("panic: unexpected thread interruption");
            }

            Semaphore mutexMyDemandedWorkplace = mutexWaitForASeat.get(wid);

            // Trying to reach the next workplace
            try {
                mutexMyDemandedWorkplace.acquire();

                if (!isAvailableToSeatAt.get(wid)) {
                    howManyWaitForASeat.compute(wid, (key, val) -> ++val); // TODO does it work?
                    Semaphore myDemandedSeatSemaphore = waitForSeat.get(wid);
                    mutexMyDemandedWorkplace.release();

                    myDemandedSeatSemaphore.acquire();

                    howManyWaitForASeat.compute(wid, (key, val) -> --val);
                }

                // TODO Move to outer? for both same and not same
                // Now the user is going to seat at and then perform use()
                isAvailableToSeatAt.replace(wid, false);

                mutexMyDemandedWorkplace.release();

            } catch (InterruptedException e) {
                throw new RuntimeException("panic: unexpected thread interruption");
            }
        }

        // TODO Moved from wid != actual
        // Update the seat, because the user is guaranteed to enter the demanded workplace
        // putActualAndPreviousWorkplace(wid, myActualWorkplace);
        previousWorkplace.replace(currentThreadId, myActualWorkplace);
        actualWorkplace.replace(currentThreadId, wid);

        return availableWorkplaces.get(wid);
       // return null;
    }

    @Override
    public void leave() {
        // After use from actual workplace
        Long currentThreadId = Thread.currentThread().getId();
        WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);

        Semaphore mutexActualWorkplace = mutexWaitForASeat.get(myActualWorkplace);

        try {
            mutexActualWorkplace.acquire();

            // It is impossible to use without entering, but now it is available for usage
            isAvailableToUse.replace(myActualWorkplace, true);

            // Others are allowed for entering
            if (howManyWaitForASeat.get(myActualWorkplace) > 0) {
                waitForSeat.get(myActualWorkplace).release();
            }
            else {
                isAvailableToSeatAt.replace(myActualWorkplace, true);
                mutexActualWorkplace.release();
            }

            actualWorkplace.remove(currentThreadId);
            previousWorkplace.remove(currentThreadId);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }


    }

    //TODO remove
    public void printout() {
        availableWorkplaces.forEachValue(Long.MAX_VALUE, (w)-> {
            System.out.println(w.getIdName());
        });
        System.out.println("decorated");
    }
}
