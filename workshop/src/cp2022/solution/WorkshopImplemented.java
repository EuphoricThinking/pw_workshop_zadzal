package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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

    // Indicates whether the user can seat at the given workplace
    private final HashMap<WorkplaceId, Boolean> isAvailableToSeatAt = new HashMap<>();
    // Idicates whether the user can start using (call use()) at the given workplace
    private final HashMap<WorkplaceId, Boolean> isAvailableToUse = new HashMap<>();

    /* Synchronization of the counter of possible number of entries to satisfy 2*N rule */
    private Semaphore mutexEntryCounter = new Semaphore(1);
    private long howManyWaitForEntry = 0;
    private Semaphore waitForEntry = new Semaphore(0, true); // FIFO semaphore

    /* Synchronization of the access to workplace data */

    private void createAvailableWorkplaceHashmap(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            availableWorkplaces.putIfAbsent(place.getId(), new WorkplaceWrapper(place.getId(), place));
        }
    }

    private void initializeWorkplaceData(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            
        }
    }

    public WorkshopImplemented(Collection<Workplace> workplaces) {
        createAvailableWorkplaceHashmap(workplaces);
        this.maxEntries = workplaces.size();
        printout();
    }

    @Override
    public Workplace enter(WorkplaceId wid) {
        return null;
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
