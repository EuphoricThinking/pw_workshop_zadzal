package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
    private long howManyWaitForEntry = 0;
    private Semaphore waitForEntry = new Semaphore(0, true); // FIFO semaphore

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
            availableWorkplaces.putIfAbsent(place.getId(), new WorkplaceWrapper(place.getId(), place));
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
                howManyWaitForEntry++;
                mutexEntryCounter.release();

                waitForEntry.acquire();

                howManyWaitForEntry--; // Sharing mutex - can be released by the thread other than the owner
            }

            mutexEntryCounter.release();
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
                howManyWaitForASeat.compute(wid, (key, val) -> val++); // TODO does it work?
                Semaphore mySeatSemaphore = waitForSeat.get(wid);
                mutexMyWorkplace.release();

                mySeatSemaphore.acquire();

                howManyWaitForASeat.compute(wid, (key, val) -> val--);
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
        return null;
    }

    @Override
    public void leave() {

    }

    //TODO remove
    public void printout() {
        availableWorkplaces.forEachValue(Long.MAX_VALUE, (w)-> {
            System.out.println(w.getIdName());
        });
        System.out.println("decorated");
    }
}
