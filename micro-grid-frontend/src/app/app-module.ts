import { NgModule, provideBrowserGlobalErrorListeners } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';

import { AppRoutingModule } from './app-routing-module';
import { App } from './app';
import { MapPanel } from './components/map-panel/map-panel';
import { DashboardPanel } from './components/dashboard-panel/dashboard-panel';
import { EquityMap } from './components/equity-map/equity-map';
import { TrendChart } from './components/trend-chart/trend-chart';
import { ConsumptionChart } from './components/consumption-chart/consumption-chart';
import { RebateChart } from './components/rebate-chart/rebate-chart';
import { Overview } from './pages/overview/overview';
import { GridSmart } from './pages/grid-smart/grid-smart';
import { CouncilLogin } from './pages/council-login/council-login';
import { CouncilDashboard } from './pages/council-dashboard/council-dashboard';
import { councilTokenInterceptor } from './interceptors/council-token-interceptor';

@NgModule({
  declarations: [
    App,
    MapPanel,
    DashboardPanel,
    EquityMap,
    TrendChart,
    ConsumptionChart,
    RebateChart,
    Overview,
    GridSmart,
    CouncilLogin,
    CouncilDashboard
  ],
  imports: [
    BrowserModule,
    CommonModule,
    FormsModule,
    AppRoutingModule
  ],
  providers: [
    provideBrowserGlobalErrorListeners(),
    // The council interceptor attaches the session token, and only to
    // /api/council - the public endpoints and Nominatim share this client.
    provideHttpClient(withFetch(), withInterceptors([councilTokenInterceptor])),
  ],
  bootstrap: [App]
})
export class AppModule { }
