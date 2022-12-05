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

    private final ConcurrentHashMap<WorkplaceId, LinkedHashSet<Long>> whoWaits_TOWARD_Workplace = new ConcurrentHashMap<>();
    // private final ConcurrentHashMap<WorkplaceId, Long> whoLeaves_FROM_Workplace = new ConcurrentHashMap<>();
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
                            waitForEntry,
                                howManyWaitForASeat,
//                                ConcurrentHashMap<WorkplaceId, Semaphore> waitForSeat,
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
        this.maxEntries = 2L *workplaces.size() - 1; //TODO experiment - 1 added
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

    public void checkIfEntryPossible() {
        try {
            mutexWaitForASeatAndEntryCounter.acquire();

            Long currentThreadId = Thread.currentThread().getId();
            // System.out.println("1 SIZE: " + entryCounter.size() + " " + currentThreadId);
            // Task completed - remove 2*N constraint for a given thread
            // entryCounter.remove(currentThreadId); // TODO moved to entrt

            // Let other users in if workplaces are available
            Iterator<Long> counterIterator = entryCounter.keySet().iterator();
            Long queuedThreadId;

            // System.out.println(Thread.currentThread().getName() + " cleanup " + actualWorkplace.get(currentThreadId));

            // TODO add information whther shared
            boolean isMutexShared = false;
            // If there is a semaphore in a queue, then there must be an entry in entryCounter
            if (counterIterator.hasNext()) {
                // If the first one must enter
              //System.out.println("check next");
                if (entryCounter.get((queuedThreadId = counterIterator.next())) == 0) {
                    // Check if the first one wants to enter (queued to enter)
                    // and its seat is available
                    Semaphore waitingToEnterSingle;
                    if (isAvailableToSeatAt.get(actualWorkplace.get(queuedThreadId))
                            && ((waitingToEnterSingle = waitForEntry.remove(queuedThreadId)) != null)) {

                        // Let that thread enter
                        isMutexShared = true;
                      //System.out.println("freed");

                        waitingToEnterSingle.release(); // Share mutex
                    }
                    // else: no one can enter
//                    else {
//                        // No-one can enter
//                        // mutexWaitForASeatAndEntryCounter.release();
//                    }
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
                      //System.out.println("release");

                        waitingToEnterSingle.release();
                    }
                    // no one of the queued has available workplace
//                    else {
//                        mutexWaitForASeatAndEntryCounter.release();
//                    }
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
        // System.out.println(Thread.currentThread().getName() + " wants to ENTER " + wid);

        // Check whether entry is possible
        try {
            mutexWaitForASeatAndEntryCounter.acquire();

            entryCounter.put(currentThreadId, maxEntries);
            usersSemaphoresForSwitchTo.put(currentThreadId, new Semaphore(0));
            isWakeupCyclic.put(currentThreadId, false);

            Iterator<Long> firstElement = entryCounter.keySet().iterator();
          //System.out.println(Thread.currentThread().getName() + " ENTRY");
            if ((!firstElement.hasNext() && entryCounter.get(firstElement.next()) == 0)
                || !isAvailableToSeatAt.get(wid)) {
              //System.out.println(Thread.currentThread().getName() + " No entries");
                    Semaphore meWaitingForEntry = new Semaphore(0);
                    waitForEntry.put(currentThreadId, meWaitingForEntry);
                    mutexWaitForASeatAndEntryCounter.release();

                    // The reference is remembered and the semaphore is pushed in the correct order
              //System.out.println(Thread.currentThread().getName() + " wait at entry");
                    meWaitingForEntry.acquire();
            }

            Iterator<Long> iterateOverQueue = entryCounter.keySet().iterator();
            Long keyVal;
            // Decrease counter values up to our key
          //System.out.println(Thread.currentThread().getName() + " ENTRY before iterate");
  //          int i = 0;
            while (iterateOverQueue.hasNext() && !(keyVal = iterateOverQueue.next()).equals(currentThreadId)) { // TODO TEST this
             // //System.out.println(i + "iter");
                entryCounter.put(keyVal, entryCounter.get(keyVal) - 1);
            }


            // entryCounter.remove(currentThreadId);
            // entrySet contains at least one key - ours, so remove() will delete the last returned key
            // iterateOverQueue.remove(); // TODO uncomment double removal?

            // entryCounter.putIfAbsent(currentThreadId, maxEntries); // TODO moved here
            // Indicate that the seat will be occupied
            isAvailableToSeatAt.replace(wid, false);

            // entryCounter.remove(currentThreadId); //TODO changed; test iterator.remove
          //System.out.println(Thread.currentThread().getName() + " ENTERING " + actualWorkplace.get(currentThreadId));

            mutexWaitForASeatAndEntryCounter.release();

            return availableWorkplaces.get(wid);
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    // Waken up with shared mutex, going to release users waiting for its seat
    private void chainWakeupNotCyclic(WorkplaceId myActualWorkplace) {
//        leavingEdges.replace(myActualWorkplace, null); // TODO zaktualizuj podczas wychodzenia
//        whoLeaves_FROM_Workplace.replace(myActualWorkplace, null);

        // Let in anyone who waits for my actual workplace
        LinkedHashSet<Long> waitsForMyPlace = whoWaits_TOWARD_Workplace.get(myActualWorkplace);
        Iterator<Long> iterateOverMyPlace = waitsForMyPlace.iterator();
        if (iterateOverMyPlace.hasNext()) {
            Long headId = iterateOverMyPlace.next();
            isWakeupCyclic.replace(headId, false);
            Semaphore headSemaphore = usersSemaphoresForSwitchTo.get(headId);
            // iterateOverMyPlace.remove(); // TODO moved

            headSemaphore.release();
        }
        else {
            isAvailableToSeatAt.replace(myActualWorkplace, true);

            mutexWaitForASeatAndEntryCounter.release();
        }
    }

    private void cyclicWakeupWithoutMutexRelease(WorkplaceId wid) {
//        whoLeaves_FROM_Workplace.put(myActualWorkplace, null); // I will make the move
//        leavingEdges.replace(myActualWorkplace, null);

        // Remove from who waits towards as if in case of  an empty place
        Long whoLeavesFromNextInCycle = whoLeaves_FROM_Workplace.get(wid);
        isWakeupCyclic.replace(whoLeavesFromNextInCycle, true);

        usersSemaphoresForSwitchTo.get(whoLeavesFromNextInCycle).release();
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
      //System.out.println(Thread.currentThread().getName() + " SWITCHING to " + wid + " seat: " + isAvailableToSeatAt.get(wid));
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

            previousWorkplace.replace(currentThreadId, myActualWorkplace);
            actualWorkplace.replace(currentThreadId, wid);

            // TODO replace
//            whoLeaves_FROM_Workplace.put(myActualWorkplace, currentThreadId);
//            leavingEdges.replace(myActualWorkplace, wid);

            // wid is an ID of the workplace I'm going to change to
            // I have NOT changed that workplace yet
            if (myActualWorkplace != wid) { // TODO changed from my previous workplace
                // Empty workplace
                if (isAvailableToSeatAt.get(wid)) {
                    isAvailableToSeatAt.replace(wid, false);

                    leavingEdges.replace(myActualWorkplace, null); // TODO zaktualizuj podczas wychodzenia
                    whoLeaves_FROM_Workplace.replace(myActualWorkplace, null);

                    chainWakeupNotCyclic(myActualWorkplace);
                }
                else {
                    whoLeaves_FROM_Workplace.put(myActualWorkplace, currentThreadId);
                    leavingEdges.replace(myActualWorkplace, wid);
                    // An added edge to wid enables precise location inside, outside a cycle
                    int cycleTest = checkCycle(myActualWorkplace);

                    if (cycleTest == noCycle || cycleTest == outsideCycle) { // both cases //TODO does it work?

                        whoWaits_TOWARD_Workplace.get(wid).add(currentThreadId);
                        mutexWaitForASeatAndEntryCounter.release();

                        usersSemaphoresForSwitchTo.get(currentThreadId).acquire();

                        // Who waits towards should be removed in the waking thread
//                        leavingEdges.replace(myActualWorkplace, null);
//                        // I get the seat which has been occupied, no need to change isAvailableToSit
//                        whoLeaves_FROM_Workplace.replace(myActualWorkplace, null);
//                        whoWaits_TOWARD_Workplace.get(wid).remove(currentThreadId); // TODO moved here
//
//                        mutexWaitForASeatAndEntryCounter.release();

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
              //System.out.println("same");
                leavingEdges.replace(myActualWorkplace, myActualWorkplace); // TODO zaktualizuj podczas wychodzenia
                whoLeaves_FROM_Workplace.replace(myActualWorkplace, currentThreadId);

                mutexWaitForASeatAndEntryCounter.release();
              //System.out.println("same released");
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
        Semaphore lastUsedWorkplace = mutexWaitToUse.get(myActualWorkplace);

        // System.out.println("LEAVING actual workplace");
        // Semaphore mutexActualWorkplace = mutexWaitForASeatAndEntry.get(myActualWorkplace);


        try {
            lastUsedWorkplace.acquire();
          //System.out.println(Thread.currentThread().getName() + "LEAVING");

            // It is impossible to use without entering, but now it is available for usage
            isAvailableToUse.replace(myActualWorkplace, true); // At most one at a given workplace
            // System.out.println("Allowed to use");

            lastUsedWorkplace.release();

          //System.out.println("acquire entry");
            mutexWaitForASeatAndEntryCounter.acquire();

            whoLeaves_FROM_Workplace.replace(myActualWorkplace, null);
            leavingEdges.replace(myActualWorkplace, null);

          //System.out.println("acquireD entry");
            // Others are allowed for entering
            LinkedHashSet<Long> waitsForMyPlace = whoWaits_TOWARD_Workplace.get(myActualWorkplace);
            Iterator<Long> iterateOverMyPlace = waitsForMyPlace.iterator();
            if (iterateOverMyPlace.hasNext()) {
                Long headId = iterateOverMyPlace.next();
                Semaphore headSemaphore = usersSemaphoresForSwitchTo.get(headId);
                iterateOverMyPlace.remove();

                headSemaphore.release();
            }
            else {
                isAvailableToSeatAt.replace(myActualWorkplace, true);

                mutexWaitForASeatAndEntryCounter.release();
            }

            this.checkIfEntryPossible();

            actualWorkplace.remove(currentThreadId);
            previousWorkplace.remove(currentThreadId);
            usersSemaphoresForSwitchTo.remove(currentThreadId);
            isWakeupCyclic.remove(currentThreadId);
          //System.out.println("Removed id");
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
