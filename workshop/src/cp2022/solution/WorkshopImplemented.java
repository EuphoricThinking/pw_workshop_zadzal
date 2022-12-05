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

    private final int noCycle = 0;
    private final int insideCycle = 1;
    private final int outsideCycle = 2;

    // Read-only map of wrapped workplaces
    private final ConcurrentHashMap<WorkplaceId, WorkplaceWrapper> availableWorkplaces = new ConcurrentHashMap<>();
    // Counter of possible number of entries to satisfy 2*N rule: <ThreadId, leftEntries>
    // serves as a queue
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

    // Entry semaphores
    private final LinkedHashMap<Long, Semaphore> waitForEntry = new LinkedHashMap<>();

    // For switchTo()
    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitForASeat = new ConcurrentHashMap<>();

    /* Synchronization of the permission to use (call use()) the given workplace */
    private final ConcurrentHashMap<WorkplaceId, Semaphore> mutexWaitToUse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, Long> howManyWaitToUse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, Semaphore> waitToUse = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<WorkplaceId, LinkedHashSet<Long>> whoWaits_TOWARD_Workplace = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Semaphore> usersSemaphoresForSwitchTo = new ConcurrentHashMap<>();
    private final HashMap<WorkplaceId, Long> whoLeaves_FROM_Workplace = new HashMap<>();
    private final HashMap<WorkplaceId, WorkplaceId> leavingEdges = new HashMap<>();
    private final HashMap<Long, Boolean> isWakeupCyclic = new HashMap<>();


    private void createAvailableWorkplaceHashmap(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            availableWorkplaces.putIfAbsent(place.getId(),
                    new WorkplaceWrapper(place.getId(), place, this,
                            entryCounter,
                            actualWorkplace,
                            previousWorkplace,
                            hasJustEntered,
                            isAvailableToSeatAt,
                            isAvailableToUse,
                            mutexWaitForASeatAndEntryCounter,
                            howManyWaitForASeat,
                            mutexWaitToUse,
                            howManyWaitToUse,
                            waitToUse));
        }
    }

    private void initializeWorkplaceData(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            WorkplaceId id = place.getId();
            isAvailableToSeatAt.putIfAbsent(id, true);
            isAvailableToUse.putIfAbsent(id, true);

            whoWaits_TOWARD_Workplace.putIfAbsent(id, new LinkedHashSet<>());
            whoLeaves_FROM_Workplace.putIfAbsent(id, null);
            leavingEdges.putIfAbsent(id, null);
        }
    }

    private void initializationOfSemaphoreMaps(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            WorkplaceId placeId = place.getId();
            waitToUse.putIfAbsent(placeId, new Semaphore(0, true));

            howManyWaitForASeat.putIfAbsent(placeId, 0L);
            howManyWaitToUse.putIfAbsent(placeId, 0L);

            mutexWaitToUse.putIfAbsent(placeId, new Semaphore(1, true));
        }
    }

    private void constructorDataInitialization(Collection<Workplace> workplaces) {
        createAvailableWorkplaceHashmap(workplaces);
        initializeWorkplaceData(workplaces);
        initializationOfSemaphoreMaps(workplaces);
    }

    public WorkshopImplemented(Collection<Workplace> workplaces) {
        this.maxEntries = 2L *workplaces.size() - 1;
        constructorDataInitialization(workplaces);
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

    public void checkIfEntryPossible() {
        try {
            mutexWaitForASeatAndEntryCounter.acquire();


            // Let other users in if workplaces are available
            Iterator<Long> counterIterator = entryCounter.keySet().iterator();
            Long queuedThreadId;

            boolean isMutexShared = false;
            // If there is a semaphore in a queue, then there must be an entry in entryCounter
            if (counterIterator.hasNext()) {
                // If the first one must enter
                if (entryCounter.get((queuedThreadId = counterIterator.next())) == 0) {
                    // Check if the first one wants to enter (queued to enter)
                    // and its seat is available
                    Semaphore waitingToEnterSingle;
                    if (isAvailableToSeatAt.get(actualWorkplace.get(queuedThreadId))
                            && ((waitingToEnterSingle = waitForEntry.remove(queuedThreadId)) != null)) {

                        // Let that thread enter
                        isMutexShared = true;

                        waitingToEnterSingle.release(); // Share mutex
                    }
                    // else: no one can enter
                } else {
                    // Late users can enter, as the queue is processed from the first entry according to insertion order
                    Iterator<Long> queuedLaterTriedEntry = waitForEntry.keySet().iterator();
                    boolean foundWorkplace = false;

                    while (queuedLaterTriedEntry.hasNext() && !foundWorkplace) {
                        queuedThreadId = queuedLaterTriedEntry.next();
                        WorkplaceId demandedWorkplace = actualWorkplace.get(queuedThreadId);

                        if (isAvailableToSeatAt.get(demandedWorkplace)) {
                            foundWorkplace = true;
                        }
                    }

                    if (foundWorkplace) {
                        Semaphore waitingToEnterSingle = waitForEntry.remove(queuedThreadId);
                        isMutexShared = true;
                      //System.out.println("FROM later release");

                        waitingToEnterSingle.release();
                    }
                    // no one of the queued has available workplace
                }
            }

            if (!isMutexShared) {
                mutexWaitForASeatAndEntryCounter.release();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    private int checkCycle(WorkplaceId actualId) {
        WorkplaceId tortoise = actualId;
        WorkplaceId hare = leavingEdges.get(leavingEdges.get(actualId));

        while (tortoise != null && hare != null && tortoise != hare) {
            tortoise = leavingEdges.get(tortoise);
            hare = leavingEdges.get(leavingEdges.get(hare));
        }

        if (tortoise == null || hare == null) {
            return noCycle;
        }
        else {
            // tortoise == hare
            hare = leavingEdges.get(hare);

            while (hare != tortoise && hare != actualId) {
                hare = leavingEdges.get(hare);
            }

            if (hare == actualId) {
                return insideCycle;
            }
            else {
                return outsideCycle;
            }
        }
    }

    @Override
    public Workplace enter(WorkplaceId wid) {
        Long currentThreadId = Thread.currentThread().getId();
        hasJustEntered.put(currentThreadId, true);
        putActualAndPreviousWorkplace(wid, wid);

        // Check whether entry is possible
        try {
            mutexWaitForASeatAndEntryCounter.acquire();

            entryCounter.put(currentThreadId, maxEntries);
            usersSemaphoresForSwitchTo.put(currentThreadId, new Semaphore(0));
            isWakeupCyclic.put(currentThreadId, false);

            Iterator<Long> firstElement = entryCounter.keySet().iterator();
            if (entryCounter.get(firstElement.next()) == 0 || !isAvailableToSeatAt.get(wid)) {
                    Semaphore meWaitingForEntry = new Semaphore(0);
                    waitForEntry.put(currentThreadId, meWaitingForEntry);
                    mutexWaitForASeatAndEntryCounter.release();

                    // The reference is remembered and the semaphore is pushed in the correct order
                    meWaitingForEntry.acquire();
            }

            Iterator<Long> iterateOverQueue = entryCounter.keySet().iterator();
            Long keyVal;
            // Decrease counter values up to our key
            while (iterateOverQueue.hasNext() && !(keyVal = iterateOverQueue.next()).equals(currentThreadId)) {
                entryCounter.put(keyVal, entryCounter.get(keyVal) - 1);
            }

            // Indicate that the seat will be occupied
            isAvailableToSeatAt.replace(wid, false);

            mutexWaitForASeatAndEntryCounter.release();

            return availableWorkplaces.get(wid);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    // Waken up with shared mutex, going to release users waiting for its seat
    private void chainWakeupNotCyclic(WorkplaceId myActualWorkplace) {
        // Let in anyone who waits for my actual workplace
        LinkedHashSet<Long> waitsForMyPlace = whoWaits_TOWARD_Workplace.get(myActualWorkplace);
        Iterator<Long> iterateOverMyPlace = waitsForMyPlace.iterator();
        if (iterateOverMyPlace.hasNext()) {
            Long headId = iterateOverMyPlace.next();
            isWakeupCyclic.replace(headId, false);
            Semaphore headSemaphore = usersSemaphoresForSwitchTo.get(headId);

            headSemaphore.release();
        }
        else {
            isAvailableToSeatAt.replace(myActualWorkplace, true);

            mutexWaitForASeatAndEntryCounter.release();
        }
    }

    private void cyclicWakeupWithoutMutexRelease(WorkplaceId wid) {
        // Remove from who waits towards as if in case of  an empty place
        Long whoLeavesFromNextInCycle = whoLeaves_FROM_Workplace.get(wid);
        isWakeupCyclic.replace(whoLeavesFromNextInCycle, true);

        usersSemaphoresForSwitchTo.get(whoLeavesFromNextInCycle).release();
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        try {
            mutexWaitForASeatAndEntryCounter.acquire();

            Long currentThreadId = Thread.currentThread().getId();

            entryCounter.put(currentThreadId, maxEntries);

            WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);

            previousWorkplace.replace(currentThreadId, myActualWorkplace);
            actualWorkplace.replace(currentThreadId, wid);

            // wid is an ID of the workplace I'm going to change to
            // I have NOT changed that workplace yet
            if (myActualWorkplace != wid) {
                // Empty workplace
                if (isAvailableToSeatAt.get(wid)) {
                    isAvailableToSeatAt.replace(wid, false);

                    leavingEdges.replace(myActualWorkplace, null);
                    whoLeaves_FROM_Workplace.replace(myActualWorkplace, null);

                    chainWakeupNotCyclic(myActualWorkplace);
                }
                else {
                    whoLeaves_FROM_Workplace.put(myActualWorkplace, currentThreadId);
                    leavingEdges.replace(myActualWorkplace, wid);
                    // An added edge to wid enables precise location inside, outside a cycle
                    int cycleTest = checkCycle(myActualWorkplace);

                    if (cycleTest == noCycle || cycleTest == outsideCycle) { // both cases

                        whoWaits_TOWARD_Workplace.get(wid).add(currentThreadId);
                        mutexWaitForASeatAndEntryCounter.release();

                        usersSemaphoresForSwitchTo.get(currentThreadId).acquire();

                        whoWaits_TOWARD_Workplace.get(wid).remove(currentThreadId);

                        whoLeaves_FROM_Workplace.put(myActualWorkplace, null); // I will make the move
                        leavingEdges.replace(myActualWorkplace, null);

                        if (!isWakeupCyclic.get(currentThreadId)) {
                            chainWakeupNotCyclic(myActualWorkplace);
                        }
                        // wid is the next one - may be the beginning of the cycle, indicated as null
                        else if (leavingEdges.get(wid) != null) {
                            cyclicWakeupWithoutMutexRelease(wid);
                        }
                        else { // The cycle has ended - found null
                            mutexWaitForASeatAndEntryCounter.release();
                        }
                    }
                    else { // Inside the cycle
                        whoLeaves_FROM_Workplace.put(myActualWorkplace, null); // I will make the move
                        leavingEdges.replace(myActualWorkplace, null);

                        cyclicWakeupWithoutMutexRelease(wid);
                    }
                }
            }
            else {
                leavingEdges.replace(myActualWorkplace, myActualWorkplace);
                whoLeaves_FROM_Workplace.replace(myActualWorkplace, currentThreadId);

                mutexWaitForASeatAndEntryCounter.release();
            }

            return availableWorkplaces.get(wid);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    @Override
    public void leave() {
        // After use from actual workplace
        Long currentThreadId = Thread.currentThread().getId();
        WorkplaceId myActualWorkplace = actualWorkplace.get(currentThreadId);
        Semaphore lastUsedWorkplace = mutexWaitToUse.get(myActualWorkplace);

        try {
            lastUsedWorkplace.acquire();

            // It is impossible to use without entering, but now it is available for usage
            isAvailableToUse.replace(myActualWorkplace, true); // At most one at a given workplace

            lastUsedWorkplace.release();

            mutexWaitForASeatAndEntryCounter.acquire();

            whoLeaves_FROM_Workplace.replace(myActualWorkplace, null);
            leavingEdges.replace(myActualWorkplace, null);

            // Others are allowed for entering
            chainWakeupNotCyclic(myActualWorkplace);

            this.checkIfEntryPossible();

            actualWorkplace.remove(currentThreadId);
            previousWorkplace.remove(currentThreadId);
            usersSemaphoresForSwitchTo.remove(currentThreadId);
            isWakeupCyclic.remove(currentThreadId);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }


    }
}
