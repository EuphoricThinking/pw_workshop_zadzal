package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class WorkshopImplemented implements Workshop {
    private final ConcurrentHashMap<WorkplaceId, Workplace> availableWorkplaces = new ConcurrentHashMap<>(); // Read-only

    private void createAvailableWorkplaceHashmap(Collection<Workplace> workplaces) {
        for (Workplace place: workplaces) {
            availableWorkplaces.putIfAbsent(place.getId(), new WorkplaceWrapper(place.getId(), place));
        }
    }

    public WorkshopImplemented(Collection<Workplace> workplaces) {
        createAvailableWorkplaceHashmap(workplaces);
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
}
