package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class WorkplaceWrapper extends Workplace {
    private final Workplace originalWorkplace;

    // Counter of possible number of entries to satisfy 2*N rule: <ThreadId, leftEntries>
    private final LinkedHashMap<Long, Long> entryCounter;

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
    private final ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToSeatAt;
    // Idicates whether the user can start using (call use()) at the given workplace
    private final ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToUse;

    /* Synchronization of the counter of possible number of entries to satisfy 2*N rule */
    private final Semaphore mutexWaitForASeatAndEntryCounter;
    //    private Long howManyWaitForEntry = 0L;
//    private Semaphore waitForEntry = new Semaphore(0, true); // FIFO semaphore
    // Entry semaphores
    // private final ArrayDeque<Semaphore> waitForEntry = new ArrayDeque<>();
    private final LinkedHashMap<Long, Semaphore> waitForEntry;

    /* Synchronization of the access to the workplace data */
    // private Semaphore mutexWorkplaceData = new Semaphore(1);
    // private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWorkplaceData = new ConcurrentHashMap<>();

    /* Synchronization of the access to the seat at the given workplace
     *  Mutex protects also workplace data isAvailableToSeat
     * */
    // private Semaphore mutexWaitForSeat = new Semaphore(1);
    // private long howManyWaitForSeat = 0;
    // private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitForASeatAndEntry = new ConcurrentHashMap<>();
    // For switchTo()
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
                                LinkedHashMap<Long, Long> entryCounter,
                                ConcurrentHashMap<Long, WorkplaceId> actualWorkplace,
                                ConcurrentHashMap<Long, WorkplaceId> previousWorkplace,
                                ConcurrentHashMap<Long, Boolean> hasJustEntered,
                                ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToSeatAt,
                                ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToUse,
                                Semaphore mutexWaitForASeatAndEntryCounter,
                                LinkedHashMap<Long, Semaphore> waitForEntry,
//                                ConcurrentHashMap<WorkplaceId, Long> howManyWaitForASeat,
//                                ConcurrentHashMap<WorkplaceId, Semaphore> waitForSeat,
                                ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse,
                                ConcurrentHashMap<WorkplaceId, Long> howManyWaitToUse,
                                ConcurrentHashMap<WorkplaceId, Semaphore> waitToUse,) {
        super(id);

        this.originalWorkplace = original;

        this.entryCounter = entryCounter;
        this.actualWorkplace = actualWorkplace;
        this.previousWorkplace = previousWorkplace;
        this.hasJustEntered = hasJustEntered;
        this.isAvailableToSeatAt = isAvailableToSeatAt;
        this.isAvailableToUse = isAvailableToUse;
        this.mutexWaitForASeatAndEntryCounter = mutexWaitForASeatAndEntryCounter;
//        this.howManyWaitForEntry = howManyWaitForEntry;
        this.waitForEntry = waitForEntry;
//        this.howManyWaitForASeat = howManyWaitForASeat;
//        this.waitForSeat = waitForSeat;
        this.howManyWaitToUse = howManyWaitToUse;
        this.waitToUse = waitToUse;
        this.mutexWaitToUse = mutexWaitToUse;
    }

    @Override
    public void use() {
        // Pre-use phase

        // The switchTo() or entry() is finished
        try {
            // Update entry 2*N constraints
            mutexWaitForASeatAndEntryCounter.acquire();

            Long currentThreadId = Thread.currentThread().getId();

            // System.out.println("1 SIZE: " + entryCounter.size() + " " + currentThreadId);
            // Task completed - remove 2*N constraint for a given thread
            entryCounter.remove(currentThreadId);

            // Let other users in if workplaces are available
            Iterator<Long> counterIterator = entryCounter.keySet().iterator();
            Long queuedThreadId;

            // TODO add information whther shared
            if (counterIterator.hasNext()) {
                // If the first one must enter
                if (entryCounter.get((queuedThreadId = counterIterator.next())) == 0) {
                    // Check if the first one wants to enter (queued to enter)
                    // and its seat is available
                    Semaphore waitingToEnterSingle;
                    if (isAvailableToUse.get(actualWorkplace.get(queuedThreadId))
                        && ((waitingToEnterSingle = waitForEntry.remove(queuedThreadId)) != null)) {

                            // Let that thread enter
                            waitingToEnterSingle.release(); // Share mutex
                    }
                    else {
                        // No-one can enter
                        mutexWaitForASeatAndEntryCounter.release();
                    }
                }
                else {
                    // Late users can enter, as the queue is processed from the first entry according to insertion order
                    Iterator<Long> queuedLaterTriedEntry = waitForEntry.keySet().iterator();
                    boolean foundWorkplace = false;

                    while (queuedLaterTriedEntry.hasNext() && !foundWorkplace) {
                        queuedThreadId = queuedLaterTriedEntry.next();
                        WorkplaceId demandedWorkplace = actualWorkplace.get(queuedThreadId);

                        if (isAvailableToUse.get(demandedWorkplace)) {
                            foundWorkplace = true;
                        }
                    }

                    if (foundWorkplace) {
                        Semaphore waitingToEnterSingle = waitForEntry.remove(queuedThreadId);
                        waitingToEnterSingle.release();
                    }
                    else {
                        mutexWaitForASeatAndEntryCounter.release();
                    }
                }
            }

            // TODO going to move to entry
            // Check if after enter()
            /*
            if (hasJustEntered.get(currentThreadId) != null) { // The map contains the key
                hasJustEntered.remove(currentThreadId);

                entryCounter.replaceAll((key, val) -> --val); // val  - 1L
            }
            */

            // System.out.println("2 SIZE: " + entryCounter.size() + " " + currentThreadId);
            // long minimumPossibleEntries = Collections.min(entryCounter.values());

            // 2*N is satisfied and there are users waiting for entry
            if (!entryCounter.isEmpty() && Collections.min(entryCounter.values()) > 0 && !waitForEntry.isEmpty()) {
                Semaphore firstInQueue = waitForEntry.remove();
                // TODO fix mutex sharing
                firstInQueue.release();
            }
            else {
                mutexEntryCounter.release();
            }

            // If the workplace has been changed - enable use() for another users
            WorkplaceId myPreviousWorkplace = previousWorkplace.get(currentThreadId);
            WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);
            if (previousWorkplace != actualWorkplace) {
                Semaphore mutexMyPreviousWorkplace = mutexWaitForASeat.get(myPreviousWorkplace);

                mutexMyPreviousWorkplace.acquire();

                // Enable to use the previous workplace
                if (howManyWaitToUse.get(myPreviousWorkplace) > 0) {
                    waitToUse.get(myPreviousWorkplace).release();
                }
                else {
                    isAvailableToUse.replace(myPreviousWorkplace, true);
                    mutexMyPreviousWorkplace.release();
                }

                Semaphore mutexMyActualWorkplace = mutexWaitForASeat.get(myActualWorkplace);
                mutexMyActualWorkplace.acquire();

                // Checks whether use() is available at the actual workplace (i.e. the previous user
                // has not completed their switchTo()
                if (!isAvailableToUse.get(myActualWorkplace)) {
                    howManyWaitToUse.compute(myActualWorkplace, (key, val) -> ++val);
                    Semaphore waitForActual = waitToUse.get(myActualWorkplace);
                    mutexMyActualWorkplace.release();

                    waitForActual.acquire();

                    howManyWaitToUse.compute(myActualWorkplace, (key, val) -> --val);
                }

                mutexMyActualWorkplace.release();
            }

            // System.out.println(Thread.currentThread().getName() + " Before ORIGINAL USE");
            originalWorkplace.use();
            // System.out.println(Thread.currentThread().getName() + " After ORIGINAL USE");
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    public WorkplaceId getIdName() {
        return originalWorkplace.getId();
    }
}
