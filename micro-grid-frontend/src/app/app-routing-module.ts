import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Overview } from './pages/overview/overview';
import { GridSmart } from './pages/grid-smart/grid-smart';
import { CouncilLogin } from './pages/council-login/council-login';
import { CouncilDashboard } from './pages/council-dashboard/council-dashboard';
import { councilGuard } from './guards/council-guard';

const routes: Routes = [
  { path: '', component: Overview, title: 'Micro-Grid · Overview' },
  { path: 'grid-smart', component: GridSmart, title: 'How to Be Grid Smart' },
  { path: 'login/council', component: CouncilLogin, title: 'Council sign-in' },
  {
    path: 'council',
    component: CouncilDashboard,
    canActivate: [councilGuard],
    title: 'Council · Energy Equity Dashboard',
  },
  { path: '**', redirectTo: '' },
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule],
})
export class AppRoutingModule {}
