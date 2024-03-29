package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class WorkplaceWrapper extends Workplace {
    private final Workplace originalWorkplace;
    private final WorkshopImplemented renderingWorkshop;

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


    /* Synchronization of the permission to use (call use()) the given workplace */
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse;
    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitToUse;
    private final ConcurrentHashMap<WorkplaceId, Semaphore> waitToUse;


    protected WorkplaceWrapper(WorkplaceId id, Workplace original, WorkshopImplemented workshop,
                                LinkedHashMap<Long, Long> entryCounter,
                                ConcurrentHashMap<Long, WorkplaceId> actualWorkplace,
                                ConcurrentHashMap<Long, WorkplaceId> previousWorkplace,
                                ConcurrentHashMap<Long, Boolean> hasJustEntered,
                                ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToSeatAt,
                                ConcurrentHashMap<WorkplaceId, Boolean> isAvailableToUse,
                                Semaphore mutexWaitForASeatAndEntryCounter,
                                ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse,
                                ConcurrentHashMap<WorkplaceId, Long> howManyWaitToUse,
                                ConcurrentHashMap<WorkplaceId, Semaphore> waitToUse) {
        super(id);

        this.originalWorkplace = original;
        this.renderingWorkshop = workshop;

        this.entryCounter = entryCounter;
        this.actualWorkplace = actualWorkplace;
        this.previousWorkplace = previousWorkplace;
        this.hasJustEntered = hasJustEntered;
        this.isAvailableToSeatAt = isAvailableToSeatAt;
        this.isAvailableToUse = isAvailableToUse;
        this.mutexWaitForASeatAndEntryCounter = mutexWaitForASeatAndEntryCounter;
        this.howManyWaitToUse = howManyWaitToUse;
        this.waitToUse = waitToUse;
        this.mutexWaitToUse = mutexWaitToUse;
    }

    @Override
    public void use() {
        // Pre-use phase

        // The switchTo() or entry() is finished
        try {
            Long currentThreadId = Thread.currentThread().getId();

            WorkplaceId myPreviousWorkplace = previousWorkplace.get(currentThreadId);
            WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);

            mutexWaitForASeatAndEntryCounter.acquire();

            // Task completed - remove 2*N constraint for a given thread
            entryCounter.remove(currentThreadId);

            mutexWaitForASeatAndEntryCounter.release();

            renderingWorkshop.checkIfEntryPossible();


            // If the workplace has been changed - enable use() for another users
            if (myPreviousWorkplace != myActualWorkplace) {
                Semaphore mutexMyPreviousWorkplace = mutexWaitToUse.get(myPreviousWorkplace);

                mutexMyPreviousWorkplace.acquire();
                // Enable to use the previous workplace
                if (howManyWaitToUse.get(myPreviousWorkplace) > 0) {
                    waitToUse.get(myPreviousWorkplace).release();
                }
                else {
                    isAvailableToUse.replace(myPreviousWorkplace, true);
                    mutexMyPreviousWorkplace.release();
                }
            }

            // If I have just entered, I have to check, whether it is possible to use()
            boolean ifHasJustEntered = (hasJustEntered.remove(currentThreadId) != null); // null if not mapped
            if (ifHasJustEntered || myPreviousWorkplace != myActualWorkplace) {
                Semaphore mutexMyActualWorkplace = mutexWaitToUse.get(myActualWorkplace);
                mutexMyActualWorkplace.acquire();

                if (!isAvailableToUse.get(myActualWorkplace)) {
                    howManyWaitToUse.compute(myActualWorkplace, (key, val) -> ++val);
                    Semaphore waitForActual = waitToUse.get(myActualWorkplace);
                    mutexMyActualWorkplace.release();

                    waitForActual.acquire();

                    howManyWaitToUse.compute(myActualWorkplace, (key, val) -> --val);
                }

                isAvailableToUse.replace(myActualWorkplace, false);

                mutexMyActualWorkplace.release();
            }

/*********************************************************************************************************************/

            originalWorkplace.use();

/*********************************************************************************************************************/

        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }
}
