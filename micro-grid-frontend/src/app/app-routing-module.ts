import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Overview } from './pages/overview/overview';
import { GridSmart } from './pages/grid-smart/grid-smart';

const routes: Routes = [
  { path: '', component: Overview, title: 'Micro-Grid · Overview' },
  { path: 'grid-smart', component: GridSmart, title: 'How to Be Grid Smart' },
  { path: '**', redirectTo: '' },
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule],
})
export class AppRoutingModule {}
