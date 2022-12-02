package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;

import java.util.ArrayDeque;
import java.util.Collections;
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
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitForASeat;
//    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitForASeat;
//    private final ConcurrentHashMap<WorkplaceId, Semaphore> waitForSeat;

    /* Synchronization of the permission to use (call use()) the given workplace
     *  Mutex protects also workplace data isAvailableToSeat
     * */
    // private Semaphore mutexWaitToUse = new Semaphore(1);
    // private long howManyWaitToUse = 0;
//    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse;
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
                               ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitForASeat,
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
        this.mutexWaitForASeat = mutexWaitForASeat;
        this.howManyWaitToUse = howManyWaitToUse;
        this.waitToUse = waitToUse;
    }

    @Override
    public void use() {
        // Pre-use phase

        // The switchTo() or entry() is finished
        try {
            // Update entry 2*N constraints
            mutexEntryCounter.acquire();

            Long currentThreadId = Thread.currentThread().getId();

            // System.out.println("1 SIZE: " + entryCounter.size() + " " + currentThreadId);
            // Task completed - remove 2*N constraint for a given thread
            entryCounter.remove(currentThreadId);

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
            System.out.println(myPreviousWorkplace + " actual: " + myActualWorkplace + " compare: " + (myActualWorkplace == myPreviousWorkplace) +
                    " equals: " + myActualWorkplace.equals(myPreviousWorkplace));
            if (myPreviousWorkplace != myActualWorkplace) {
                System.out.println("previous is not actual " + Thread.currentThread().getName());
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

                // TODO moved below
                /*
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
                 */
            }

            // TODO added
            // If I have just entered, I have to check, whether it is possible to use()
            Boolean ifHasJustEntered = (hasJustEntered.remove(currentThreadId) != null); // null if not mapped
            // if (hasJustEntered.get(currentThreadId) != null || previousWorkplace != actualWorkplace) {// The map contains the key
            if (ifHasJustEntered || myPreviousWorkplace != myActualWorkplace) {
                if (ifHasJustEntered) {
                    System.out.println(Thread.currentThread().getName() + " USE AFTER ENTER");
                }
                // hasJustEntered.remove(currentThreadId);

                Semaphore mutexMyActualWorkplace = mutexWaitForASeat.get(myActualWorkplace);
                mutexMyActualWorkplace.acquire();

                System.out.println("Will I stop at " + actualWorkplace.get(currentThreadId) + "? " + Thread.currentThread().getName()  + " use: " +
                        isAvailableToUse.get(actualWorkplace.get(currentThreadId)));

                // Checks whether use() is available at the actual workplace (i.e. the previous user
                // has not completed their switchTo()
                if (!isAvailableToUse.get(myActualWorkplace)) {
                    howManyWaitToUse.compute(myActualWorkplace, (key, val) -> ++val);
                    Semaphore waitForActual = waitToUse.get(myActualWorkplace);
                    mutexMyActualWorkplace.release();

                    System.out.println(Thread.currentThread().getName() + " needs to stop");

                    waitForActual.acquire();

                    System.out.println(Thread.currentThread().getName()  + " RELEASED use: " +
                            isAvailableToUse.get(actualWorkplace.get(currentThreadId)));

                    howManyWaitToUse.compute(myActualWorkplace, (key, val) -> --val);
                }

                // TODO added
                isAvailableToUse.replace(myActualWorkplace, false);

                mutexMyActualWorkplace.release();
            }

            System.out.println("USE " + actualWorkplace.get(currentThreadId) + " by " + Thread.currentThread().getName()  + " use: " +
                    isAvailableToUse.get(actualWorkplace.get(currentThreadId)));
            System.out.println(Thread.currentThread().getName() + " Before ORIGINAL USE " + actualWorkplace.get(currentThreadId));
            originalWorkplace.use();
            System.out.println(Thread.currentThread().getName() + " After ORIGINAL USE " + actualWorkplace.get(currentThreadId));
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    public WorkplaceId getIdName() {
        return originalWorkplace.getId();
    }
}
