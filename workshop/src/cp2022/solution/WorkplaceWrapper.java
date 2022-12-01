package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class WorkplaceWrapper extends Workplace {
    private final Workplace originalWorkplace;

    // Counter of possible number of entries to satisfy 2*N rule: <ThreadId, leftEntries>
    private final HashMap<Long, Long> entryCounter;

    /* Every entry is changed only by a single thread, whose id is the key in a map */
    // Actual workplace a given thread is seated at: <ThreadId, WorkplaceId>
    private final ConcurrentHashMap<Long, WorkplaceId> actualWorkplace;
    // Actual workplace a given thread is seated at: <ThreadId, WorkplaceId>
    private final ConcurrentHashMap<Long, WorkplaceId> previousWorkplace;
    // Enables distinction between entering and switching to users; in ConcurrentHashMap it is impossible
    // to put null as a value or key, therefore checking condition previousWorkplace == null
    // for entering users throws a NullPointerException
    private final ConcurrentHashMap<Long, Boolean> hasJustEntered;

    /* Workplace data */
    // Indicates whether the user can seat at the given workplace
//    private final ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToSeatAt;
    // Idicates whether the user can start using (call use()) at the given workplace
    private final ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToUse;

    /* Synchronization of the counter of possible number of entries to satisfy 2*N rule */
    private Semaphore mutexEntryCounter;
    // private long howManyWaitForEntry;
    private ArrayDeque<Semaphore> waitForEntry; // FIFO semaphore

    /* Synchronization of the access to the workplace data */
    // private Semaphore mutexWorkplaceData = new Semaphore(1);
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWorkplaceData;

    /* Synchronization of the access to the seat at the given workplace
     *  Mutex protects also workplace data isAvailableToSeat
     * */
    // private Semaphore mutexWaitForSeat = new Semaphore(1);
    // private long howManyWaitForSeat = 0;
//    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitForASeat;
//    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitForASeat;
//    private final ConcurrentHashMap<WorkplaceId, Semaphore> waitForSeat;

    /* Synchronization of the permission to use (call use()) the given workplace
     *  Mutex protects also workplace data isAvailableToSeat
     * */
    // private Semaphore mutexWaitToUse = new Semaphore(1);
    // private long howManyWaitToUse = 0;
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse;
    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitToUse;
    private final ConcurrentHashMap<WorkplaceId, Semaphore> waitToUse;


    protected WorkplaceWrapper(WorkplaceId id, Workplace original,
                               HashMap<Long, Long> entryCounter,
                               ConcurrentHashMap<Long, WorkplaceId> actualWorkplace,
                               ConcurrentHashMap<Long, WorkplaceId> previousWorkplace,
                               ConcurrentHashMap<Long, Boolean> hasJustEntered,
//                               ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToSeatAt,
                               ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToUse,
                               Semaphore mutexEntryCounter,
//                               Long howManyWaitForEntry,
                               ArrayDeque<Semaphore> waitForEntry,
                               ConcurrentHashMap<WorkplaceId, Semaphore> mutexWorkplaceData,
                               ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse,
                               ConcurrentHashMap<WorkplaceId, Long> howManyWaitToUse,
                               ConcurrentHashMap<WorkplaceId, Semaphore> waitToUse) {
        super(id);

        this.originalWorkplace = original;

        this.entryCounter = entryCounter;
        this.actualWorkplace = actualWorkplace;
        this.previousWorkplace = previousWorkplace;
        this.hasJustEntered = hasJustEntered;
//        this.isAvailableToSeatAt = isAvailableToSeatAt;
        this.isAvailableToUse = isAvailableToUse;
        this.mutexEntryCounter = mutexEntryCounter;
//        this.howManyWaitForEntry = howManyWaitForEntry;
        this.waitForEntry = waitForEntry;
        this.mutexWorkplaceData = mutexWorkplaceData;
        this.mutexWaitToUse = mutexWaitToUse;
        this.howManyWaitToUse = howManyWaitToUse;
        this.waitToUse = waitToUse;
    }

    @Override
    public void use() {
    }

    public WorkplaceId getIdName() {
        return originalWorkplace.getId();
    }
}
