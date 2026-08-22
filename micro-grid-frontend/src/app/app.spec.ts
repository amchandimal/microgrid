import { TestBed } from '@angular/core/testing';
import { RouterModule } from '@angular/router';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { App } from './app';
import { Overview } from './pages/overview/overview';

describe('App shell', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RouterModule.forRoot([])],
      declarations: [App],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('should offer the Grid Smart wizard in the nav', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const links = [...fixture.nativeElement.querySelectorAll('.topbar__nav a')] as HTMLElement[];
    const labels = links.map((a) => a.textContent?.trim());

    expect(labels).toContain('How to Be Grid Smart');
    expect(labels).toContain('Overview');
    const wizard = links.find((a) => a.textContent?.trim() === 'How to Be Grid Smart');
    expect(wizard?.getAttribute('href')).toBe('/grid-smart');
  });

  it('should render the routed page into an outlet', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.shell__body router-outlet')).toBeTruthy();
  });
});

describe('Overview page', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [Overview],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();
  });

  it('should hold the map and the dashboard side by side', async () => {
    const fixture = TestBed.createComponent(Overview);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.split__left app-map-panel')).toBeTruthy();
    expect(el.querySelector('.split__right app-dashboard-panel')).toBeTruthy();
  });
});
