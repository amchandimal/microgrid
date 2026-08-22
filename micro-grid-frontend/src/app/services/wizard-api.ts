import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { GridStatus, OutcomePlan, WizardAnswers } from '../models/grid.models';

/** The Energy Outcome Wizard's backend. */
@Injectable({ providedIn: 'root' })
export class WizardApi {
  private readonly http = inject(HttpClient);

  /** Live supply and demand around a point the address geocoded to. */
  status(lat: number, lng: number): Observable<GridStatus> {
    return this.http.get<GridStatus>('/api/grid/status', {
      params: new HttpParams({ fromObject: { lat, lng } }),
    });
  }

  /** Build the ranked plan. */
  plan(answers: WizardAnswers): Observable<OutcomePlan> {
    return this.http.post<OutcomePlan>('/api/wizard/plan', answers);
  }
}
