import { NgModule, provideBrowserGlobalErrorListeners } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { provideHttpClient, withFetch } from '@angular/common/http';

import { AppRoutingModule } from './app-routing-module';
import { App } from './app';
import { MapPanel } from './components/map-panel/map-panel';
import { DashboardPanel } from './components/dashboard-panel/dashboard-panel';
import { Overview } from './pages/overview/overview';
import { GridSmart } from './pages/grid-smart/grid-smart';

@NgModule({
  declarations: [
    App,
    MapPanel,
    DashboardPanel,
    Overview,
    GridSmart
  ],
  imports: [
    BrowserModule,
    FormsModule,
    AppRoutingModule
  ],
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch()),
  ],
  bootstrap: [App]
})
export class AppModule { }
