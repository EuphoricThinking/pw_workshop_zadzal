/*
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment
 *
 * Author: Konrad Iwanicki (iwanicki@mimuw.edu.pl)
 */
package cp2022.solution;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;


public final class WorkshopFactory {

    public final static Workshop newWorkshop(
            Collection<Workplace> workplaces
    ) {

        // FIXME: implement
        return new WorkshopImplemented(workplaces);

       // throw new RuntimeException("not implemented");
    }
    
}
