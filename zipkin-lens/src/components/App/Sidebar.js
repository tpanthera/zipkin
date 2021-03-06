import PropTypes from 'prop-types';
import React from 'react';
import { withRouter } from 'react-router';
import { Link } from 'react-router-dom';
import Cookies from 'js-cookie';

import Logo from '../../img/zipkin-logo.svg';

const propTypes = {
  location: PropTypes.shape({
    pathname: PropTypes.string,
  }).isRequired,
  history: PropTypes.shape({
    push: PropTypes.func.isRequired,
  }).isRequired,
};

const pageInfo = {
  browser: {
    url: '/zipkin',
    label: 'Search',
    icon: 'fa-search',
  },
  dependencies: {
    url: '/zipkin/dependency',
    label: 'Dependencies',
    icon: 'fa-code-branch',
  },
};

class Sidebar extends React.Component {
  constructor(props) {
    super(props);
    this.goBackToClassic = this.goBackToClassic.bind(this);
  }

  goBackToClassic(event) {
    const { location, history } = this.props;

    Cookies.remove('lens');
    if (location.pathname === '/zipkin') {
      history.push('/zipkin/');
    } else {
      history.push(`${location.pathname}`);
    }
    window.location.reload(true);
    event.preventDefault();
  }

  renderPageOption(pageName) {
    const { location } = this.props;
    const { url } = pageInfo[pageName];
    const isSelected = location.pathname === url;
    return (
      <div className={`sidebar__page-option${isSelected ? '--selected' : ''}`}>
        <Link
          to={{ pathname: url }}
          className={`sidebar__page-option-link${isSelected ? '--selected' : ''}`}
        >
          <div className="sidebar__page-option-icon">
            <i className={`fas ${pageInfo[pageName].icon}`} />
          </div>
          <div>
            {pageInfo[pageName].label}
          </div>
        </Link>
      </div>
    );
  }

  render() {
    return (
      <div className="sidebar">
        <div
          to={{ pathname: '' }}
          className="sidebar__brand-link"
        >
          <Logo className="sidebar__brand-logo" />
        </div>
        <div className="sidebar__menu">
          {this.renderPageOption('browser')}
          {this.renderPageOption('dependencies')}
        </div>
        {
          Cookies.get('lens')
            ? (
              <div className="sidebar__go-back-to-classic-button-wrapper">
                <button
                  type="button"
                  className="sidebar__go-back-to-classic-button"
                  onClick={this.goBackToClassic}
                >
                  Go back to classic Zipkin
                </button>
              </div>
            )
            : null
        }
        <div className="sidebar__other-links">
          <a href="https://zipkin.apache.org/" target="_blank" rel="noopener noreferrer">
            <div className="sidebar__other-link fas fa-home" />
          </a>
          <a href="https://github.com/openzipkin/zipkin" target="_blank" rel="noopener noreferrer">
            <div className="sidebar__other-link fab fa-github" />
          </a>
          <a href="https://twitter.com/zipkinproject" target="_blank" rel="noopener noreferrer">
            <div className="sidebar__other-link fab fa-twitter" />
          </a>
          <a href="https://gitter.im/openzipkin/zipkin/" target="_blank" rel="noopener noreferrer">
            <div className="sidebar__other-link fab fa-gitter" />
          </a>
        </div>
      </div>
    );
  }
}

Sidebar.propTypes = propTypes;

export default withRouter(Sidebar);
